"""Real signed suspension/wake protocol; no model calls or published problems."""
import hashlib,hmac,json,subprocess,sys,time,urllib.request,uuid
from pathlib import Path
sys.path.insert(0,'/usr/local/lib/nemo-ops')
from common import mysql,atomic_json
stage=Path(sys.argv[1]);task=str(uuid.uuid4());worker='event-acceptance-'+str(uuid.uuid4())
m=json.loads(subprocess.check_output(['docker','inspect','nemo-oj-question-service-1']))[0]
env=dict(x.split('=',1) for x in m['Config']['Env']);ip=next(iter(m['NetworkSettings']['Networks'].values()))['IPAddress']
secret=env['AI_INTERNAL_SECRET'].encode();fixture=json.loads(Path('/home/ubuntu/nemo-generation-20261011/fixtures.json').read_text())['prefix_matrix']
fixture['generation']['protocol']='CANONICAL_PYTHON_PREFLIGHT_V1'
def post(suffix,body):
    path='/api/question/internal/ai/tasks/'+task+suffix;data=json.dumps(body,separators=(',',':'),ensure_ascii=False).encode();stamp=str(int(time.time()))
    sig=hmac.new(secret,(stamp+'\nPOST\n'+path+'\n'+hashlib.sha256(data).hexdigest()).encode(),hashlib.sha256).hexdigest()
    return json.load(urllib.request.urlopen(urllib.request.Request('http://'+ip+':8103'+path,data=data,headers={'Content-Type':'application/json','X-Agent-Timestamp':stamp,'X-Agent-Signature':sig}),timeout=30))
mysql("INSERT INTO ai_task(id,type,idempotencyKey,status,stage,startedAt,leaseOwner,leaseExpireAt,attemptCount) VALUES ('"+task+"','PRIVATE_PROBLEM','"+task+"','RUNNING','PRECHECK',NOW(),'"+worker+"',DATE_ADD(NOW(),INTERVAL 15 MINUTE),1)")
report={'taskId':task,'modelCalls':0}
try:
    submitted=post('/validations',{'workerId':worker,'revision':1,'privateProblem':fixture});assert submitted['code']==0;job=submitted['data']['validationId']
    bad=post('/heartbeat',{'workerId':worker,'stage':'WAIT_VALIDATION','validationId':str(uuid.uuid4())});assert bad['errorKey']=='EDIT_CONFLICT';report['wrongJobDenied']=True
    response=post('/heartbeat',{'workerId':worker,'stage':'WAIT_VALIDATION','validationId':job});assert response['code']==0
    row=mysql("SELECT status,stage,COALESCE(leaseOwner,'NONE'),TIMESTAMPDIFF(SECOND,startedAt,leaseExpireAt) FROM ai_task WHERE id='"+task+"'").split('\t')
    assert row==['RUNNING','WAIT_VALIDATION','NONE','900'];report['absoluteDeadlinePreserved']=True
    assert post('/heartbeat',{'workerId':worker,'stage':'WAIT_VALIDATION','validationId':job})['code']==0;report['waitReplayIdempotent']=True
    stale=post('/heartbeat',{'workerId':worker,'stage':'KEEP'});assert stale['errorKey']=='AI_LEASE_LOST';report['staleHeartbeatDenied']=True
    deadline=time.monotonic()+120
    while time.monotonic()<deadline:
        row=mysql("SELECT status,stage FROM ai_task WHERE id='"+task+"'").split('\t')
        if row==['QUEUED','VALIDATION_READY']:break
        time.sleep(.5)
    else:raise RuntimeError('Validation wake missing')
    # Agent event flag remains disabled during this synthetic protocol check.
    # Claim normally instead of relying on unsigned SQL to take an owned lease.
    resumed=worker+'-resumed';assert post('/claim',{'workerId':resumed})['data']['claimed']
    assert mysql("SELECT attemptCount FROM ai_task WHERE id='"+task+"'")=='1';report['resumeNotCountedAsRetry']=True
    proof=post('/validations/'+job,{'workerId':resumed})['data']['result'];assert proof['pythonPreflightValidated'] and not proof['runtimeValidated']
    denied=post('/complete',{'workerId':resumed,'privateProblem':fixture,'inputTokens':0,'outputTokens':0,'estimatedCostUsd':0});assert denied['errorKey']=='VALIDATION_REQUIRED';report['partialPublicationDenied']=True
    assert mysql("SELECT COUNT(*) FROM ai_outbox WHERE taskId='"+task+"' AND eventType='VALIDATION_1'")=='1';report['oneRevisionEvent']=True
    report['passed']=True
finally:
    pending=int(mysql("SELECT COUNT(*) FROM private_validation_job WHERE taskId='"+task+"' AND status IN ('QUEUED','RUNNING')"))
    if pending==0:
        assert mysql("SELECT COUNT(*) FROM ai_model_call WHERE taskId='"+task+"'")=='0'
        assert mysql("SELECT COUNT(*) FROM ai_task WHERE id='"+task+"' AND ownerUserId IS NULL AND resourceId IS NULL")=='1'
        # Do not enqueue this protocol-only task into the live Agent.
        mysql("START TRANSACTION;DELETE c FROM private_validation_checkpoint c JOIN private_validation_job v ON v.id=c.validationId WHERE v.taskId='"+task+"';DELETE FROM private_validation_job WHERE taskId='"+task+"';DELETE FROM ai_outbox WHERE taskId='"+task+"';DELETE FROM ai_task WHERE id='"+task+"' AND ownerUserId IS NULL;COMMIT;")
        report['cleanupComplete']=True
    else:report['cleanupDeferred']=True
    atomic_json(stage/'event-protocol.json',report)
print(json.dumps(report),flush=True)
