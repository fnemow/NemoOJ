"""Real same-task repair evidence acceptance. No live Agent pause, model calls or public publication."""
import hashlib,json,subprocess,sys,time,urllib.request,uuid
from pathlib import Path
sys.path.insert(0,'/usr/local/lib/nemo-ops')
from common import mysql,atomic_json
stage=Path(sys.argv[1]);task=str(uuid.uuid4());worker='round6-manual-'+str(uuid.uuid4());foreign=None
fixture=json.loads(Path('/home/ubuntu/nemo-generation-20261011/fixtures.json').read_text())['prefix_matrix']
fixture['generation'].pop('templateId',None);fixture['generation'].pop('templateVersion',None)
original=json.loads(json.dumps(fixture));bad=json.loads(json.dumps(fixture));bad['referenceSolutions']['cpp:FUNCTION']='class Solution { invalid };'
def quote(v):return "'"+str(v).replace('\\','\\\\').replace("'","''")+"'"
def serialized(d):return json.dumps(d,separators=(',',':'),ensure_ascii=False)
def meta(name):return json.loads(subprocess.check_output(['docker','inspect','nemo-oj-'+name+'-1']))[0]
m=meta('judge-service');ip=next(iter(m['NetworkSettings']['Networks'].values()))['IPAddress'];env=dict(x.split('=',1) for x in m['Config']['Env'])
def validate(d,job,token):
 q={'id':0,'problemVersion':1,'title':d['title'],'content':d['contentMarkdown'],'judgeCase':d['judgeCases'],'judgeConfig':d['judgeConfig'],'problemSpec':d['problemSpec']}
 body={'question':q,'referenceSolutions':d['referenceSolutions'],'oracleSolution':d['generation']['oracleSolution'],'generationProtocol':d['generation']['protocol'],'validationId':job,'validationLeaseToken':token}
 raw=json.load(urllib.request.urlopen(urllib.request.Request('http://'+ip+':8104/api/judge/inner/private-problem/validate',data=serialized(body).encode(),headers={'Content-Type':'application/json','X-Internal-Token':env['INTERNAL_AUTH_SECRET']}),timeout=480))
 if 'code' in raw:assert raw['code']==0,'Validator rejected operator fixture'
 return raw.get('data',raw)
def create_job(d,revision):
 job=str(uuid.uuid4());token=str(uuid.uuid4());encoded=serialized(d)
 mysql("INSERT INTO private_validation_job(id,taskId,revision,draftHash,draftJson,status,leaseToken,leaseExpireAt,startedAt,attemptCount) VALUES ("+','.join([quote(job),quote(task),str(revision),quote(hashlib.sha256(encoded.encode()).hexdigest()),quote(encoded),"'RUNNING'",quote(token),"DATE_ADD(NOW(),INTERVAL 10 MINUTE)","NOW()","1"])+")")
 return job,token
mysql("INSERT INTO ai_task(id,type,idempotencyKey,status,stage,startedAt,leaseOwner,leaseExpireAt,attemptCount) VALUES ("+quote(task)+",'PRIVATE_PROBLEM',"+quote(task)+",'RUNNING','MANUAL_ACCEPTANCE',NOW(),"+quote(worker)+",DATE_ADD(NOW(),INTERVAL 15 MINUTE),1)")
r={'taskId':task,'modelCalls':0,'publishedProblems':0}
try:
 job1,token1=create_job(bad,1);start=time.monotonic();failed=validate(bad,job1,token1)
 assert not failed.get('passed') and failed['category']=='COMPILE' and failed['combination']=='cpp:FUNCTION'
 count=int(mysql('SELECT COUNT(*) FROM private_validation_checkpoint WHERE validationId='+quote(job1)));assert count>=5
 r['failedRevision']={'seconds':round(time.monotonic()-start,3),'category':'COMPILE','target':'cpp:FUNCTION','successfulExecutions':count}
 mysql("UPDATE private_validation_job SET status='FAILED',completedAt=NOW(),resultJson="+quote(serialized({'passed':False,'category':'COMPILE'}))+" WHERE id="+quote(job1))
 job2,token2=create_job(original,2);start=time.monotonic();passed=validate(original,job2,token2)
 assert passed.get('passed') and passed.get('oracleValidated') and len(passed.get('combinations',[]))==6
 count2=int(mysql('SELECT COUNT(*) FROM private_validation_checkpoint WHERE validationId='+quote(job2)))
 assert count2==2,'Unchanged successful programs were executed again'
 r['repairedRevision']={'seconds':round(time.monotonic()-start,3),'passed':True,'newExecutions':count2,'reusedExecutions':count,'sixCombinations':True}
 mysql("UPDATE private_validation_job SET status='SUCCEEDED',completedAt=NOW(),resultJson='{}' WHERE id="+quote(job2))
 # A different task cannot retrieve old-task evidence, even with the exact hash.
 hash_value=mysql('SELECT executionHash FROM private_validation_checkpoint WHERE validationId='+quote(job1)+' LIMIT 1')
 current_job=str(uuid.uuid4());foreign=str(uuid.uuid4());lease=str(uuid.uuid4())
 mysql("INSERT INTO ai_task(id,type,idempotencyKey,status,stage,startedAt,leaseExpireAt) VALUES ("+quote(foreign)+",'PRIVATE_PROBLEM',"+quote(foreign)+",'RUNNING','MANUAL_ACCEPTANCE',NOW(),DATE_ADD(NOW(),INTERVAL 10 MINUTE))")
 encoded=serialized(original)
 mysql("INSERT INTO private_validation_job(id,taskId,revision,draftHash,draftJson,status,leaseToken,leaseExpireAt) VALUES ("+','.join([quote(current_job),quote(foreign),'1',quote(hashlib.sha256(encoded.encode()).hexdigest()),quote(encoded),"'RUNNING'",quote(lease),"DATE_ADD(NOW(),INTERVAL 10 MINUTE)"])+")")
 qm=meta('question-service');qe=dict(x.split('=',1) for x in qm['Config']['Env']);qip=next(iter(qm['NetworkSettings']['Networks'].values()))['IPAddress']
 url='http://'+qip+':8103/api/question/inner/private-validation/checkpoint?validationId='+current_job+'&executionHash='+hash_value
 raw=urllib.request.urlopen(urllib.request.Request(url,headers={'X-Internal-Token':qe['INTERNAL_AUTH_SECRET']}),timeout=10).read()
 value=json.loads(raw) if raw else None
 assert value is None or value.get('data',value) is None
 r['crossTaskReuseDenied']=True
 mysql("UPDATE private_validation_job SET status='CANCELLED' WHERE taskId="+quote(foreign))
 mysql("DELETE FROM private_validation_job WHERE taskId="+quote(foreign));mysql("DELETE FROM ai_task WHERE id="+quote(foreign)+" AND ownerUserId IS NULL AND idempotencyKey="+quote(foreign))
 r['passed']=True
finally:
 # Operator-authored unowned tasks never enter the model queue.
 mysql("UPDATE private_validation_job SET status='CANCELLED' WHERE taskId="+quote(task)+" AND status='RUNNING'")
 assert mysql("SELECT COUNT(*) FROM ai_model_call WHERE taskId="+quote(task))=='0'
 mysql("START TRANSACTION;DELETE c FROM private_validation_checkpoint c JOIN private_validation_job v ON v.id=c.validationId WHERE v.taskId="+quote(task)+";DELETE FROM private_validation_job WHERE taskId="+quote(task)+";DELETE FROM ai_outbox WHERE taskId="+quote(task)+";DELETE FROM ai_task WHERE id="+quote(task)+" AND ownerUserId IS NULL AND resourceId IS NULL AND idempotencyKey="+quote(task)+";COMMIT;")
 r['cleanupComplete']=True;atomic_json(stage/'repair.json',r)
 if foreign is not None:
  mysql("START TRANSACTION;DELETE c FROM private_validation_checkpoint c JOIN private_validation_job v ON v.id=c.validationId WHERE v.taskId="+quote(foreign)+";DELETE FROM private_validation_job WHERE taskId="+quote(foreign)+";DELETE FROM ai_task WHERE id="+quote(foreign)+" AND ownerUserId IS NULL AND idempotencyKey="+quote(foreign)+";COMMIT;")
print(json.dumps(r),flush=True)
