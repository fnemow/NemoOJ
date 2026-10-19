"""Fresh task, same repaired source/inputs/limits; no prior evidence and no compile-cache scope."""
import hashlib,json,subprocess,sys,time,urllib.request,uuid
from pathlib import Path
sys.path.insert(0,'/usr/local/lib/nemo-ops')
from common import mysql,atomic_json
stage=Path(sys.argv[1]);task=str(uuid.uuid4());job=str(uuid.uuid4());lease=str(uuid.uuid4())
d=json.loads(Path('/home/ubuntu/nemo-generation-20261011/fixtures.json').read_text())['prefix_matrix']
d['generation'].pop('templateId',None);d['generation'].pop('templateVersion',None)
def quote(v):return "'"+str(v).replace('\\','\\\\').replace("'","''")+"'"
encoded=json.dumps(d,separators=(',',':'),ensure_ascii=False)
mysql("INSERT INTO ai_task(id,type,idempotencyKey,status,stage,startedAt,leaseExpireAt) VALUES ("+quote(task)+",'PRIVATE_PROBLEM',"+quote(task)+",'RUNNING','MANUAL_ACCEPTANCE',NOW(),DATE_ADD(NOW(),INTERVAL 15 MINUTE))")
mysql("INSERT INTO private_validation_job(id,taskId,revision,draftHash,draftJson,status,leaseToken,leaseExpireAt,startedAt,attemptCount) VALUES ("+','.join([quote(job),quote(task),'1',quote(hashlib.sha256(encoded.encode()).hexdigest()),quote(encoded),"'RUNNING'",quote(lease),"DATE_ADD(NOW(),INTERVAL 10 MINUTE)","NOW()","1"])+")")
r={'modelCalls':0}
try:
 m=json.loads(subprocess.check_output(['docker','inspect','nemo-oj-judge-service-1']))[0];e=dict(v.split('=',1) for v in m['Config']['Env']);ip=next(iter(m['NetworkSettings']['Networks'].values()))['IPAddress']
 q={'id':0,'problemVersion':1,'title':d['title'],'content':d['contentMarkdown'],'judgeCase':d['judgeCases'],'judgeConfig':d['judgeConfig'],'problemSpec':d['problemSpec']}
 body={'question':q,'referenceSolutions':d['referenceSolutions'],'oracleSolution':d['generation']['oracleSolution'],'generationProtocol':d['generation']['protocol'],'validationId':job,'validationLeaseToken':lease}
 start=time.monotonic();raw=json.load(urllib.request.urlopen(urllib.request.Request('http://'+ip+':8104/api/judge/inner/private-problem/validate',data=json.dumps(body).encode(),headers={'Content-Type':'application/json','X-Internal-Token':e['INTERNAL_AUTH_SECRET']}),timeout=480))
 value=raw.get('data',raw);assert value.get('passed') and value.get('oracleValidated') and len(value.get('combinations',[]))==6
 count=int(mysql('SELECT COUNT(*) FROM private_validation_checkpoint WHERE validationId='+quote(job)));assert count==7
 r.update(passed=True,seconds=round(time.monotonic()-start,3),newExecutions=count,sixCombinations=True)
finally:
 mysql("START TRANSACTION;DELETE c FROM private_validation_checkpoint c JOIN private_validation_job v ON v.id=c.validationId WHERE v.taskId="+quote(task)+";DELETE FROM private_validation_job WHERE taskId="+quote(task)+";DELETE FROM ai_task WHERE id="+quote(task)+" AND ownerUserId IS NULL AND resourceId IS NULL AND idempotencyKey="+quote(task)+";COMMIT;")
 r['cleanupComplete']=True;atomic_json(stage/'repair-full-baseline.json',r)
print(json.dumps(r),flush=True)
