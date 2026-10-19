"""Authored candidate only. Real signed job/publication boundaries, no model or user data."""
import copy
import hashlib
import hmac
import json
import subprocess
import sys
import time
import urllib.request
import uuid
from pathlib import Path
sys.path.insert(0,'/usr/local/lib/nemo-ops')
from common import mysql


def main(stage):
    stage=Path(stage);fixture=json.loads(Path('/home/ubuntu/nemo-generation-20261011/fixtures.json').read_text())['prefix_matrix']
    meta=json.loads(subprocess.check_output(['docker','inspect','nemo-oj-question-service-1']))[0]
    env=dict(x.split('=',1) for x in meta['Config']['Env']);ip=next(iter(meta['NetworkSettings']['Networks'].values()))['IPAddress']
    secret=env['AI_INTERNAL_SECRET'].encode();task=str(uuid.uuid4());worker='preflight-acceptance';jobs=[]
    report={'taskId':task,'modelCalls':0,'steps':[]};output=stage/'acceptance.json'
    mysql("INSERT INTO ai_task(id,type,idempotencyKey,status,stage,startedAt,leaseOwner,leaseExpireAt) VALUES ('"+task+"','PRIVATE_PROBLEM','"+task+"','RUNNING','PRECHECK',NOW(),'"+worker+"',DATE_ADD(NOW(),INTERVAL 15 MINUTE))")

    def post(suffix,body):
        path='/api/question/internal/ai/tasks/'+task+suffix
        data=json.dumps(body,ensure_ascii=False,separators=(',',':')).encode();stamp=str(int(time.time()))
        signature=hmac.new(secret,(stamp+'\nPOST\n'+path+'\n'+hashlib.sha256(data).hexdigest()).encode(),hashlib.sha256).hexdigest()
        request=urllib.request.Request('http://'+ip+':8103'+path,data=data,headers={'Content-Type':'application/json','X-Agent-Timestamp':stamp,'X-Agent-Signature':signature})
        with urllib.request.urlopen(request,timeout=30) as response:return json.load(response)

    def validate(revision,draft):
        started=time.monotonic();answer=post('/validations',{'workerId':worker,'revision':revision,'privateProblem':draft})
        assert answer['code']==0,answer.get('errorKey');job=answer['data'];jobs.append(job['validationId'])
        while job['status'] not in ('SUCCEEDED','FAILED','CANCELLED'):
            assert time.monotonic()-started<840
            post('/heartbeat',{'workerId':worker,'stage':'KEEP'})
            time.sleep(2);answer=post('/validations/'+job['validationId'],{'workerId':worker});assert answer['code']==0;job=answer['data']
        result=job['result'];row={'phase':'python' if revision==1 else 'full','seconds':round(time.monotonic()-started,3),'passed':result.get('passed'),'category':result.get('category'),'runtimeValidated':result.get('runtimeValidated'),'oracleValidated':result.get('oracleValidated'),'pythonPreflightValidated':result.get('pythonPreflightValidated'),'pythonEvidenceReused':result.get('pythonEvidenceReused'),'combinations':result.get('combinations'),'reusedCombinations':result.get('reusedCombinations'),'checkpointCount':int(mysql("SELECT COUNT(*) FROM private_validation_checkpoint WHERE validationId='"+job['validationId']+"'"))}
        report['steps'].append(row);output.write_text(json.dumps(report,indent=2));print(json.dumps(row),flush=True)
        assert result['passed'];return result

    try:
        pre=copy.deepcopy(fixture);pre['generation']['protocol']='CANONICAL_PYTHON_PREFLIGHT_V1'
        pre['referenceSolutions']['java:FUNCTION']='class Solution { /* deferred */ }'
        pre['referenceSolutions']['cpp:FUNCTION']='class Solution { /* deferred */ };'
        result=validate(1,pre);assert result['pythonPreflightValidated'] and result['runtimeValidated'] is False
        assert report['steps'][0]['checkpointCount']==2
        answer=post('/complete',{'workerId':worker,'privateProblem':pre,'inputTokens':0,'outputTokens':0,'estimatedCostUsd':0})
        assert answer.get('errorKey')=='VALIDATION_REQUIRED';report['partialPublicationDenied']=True
        changed=copy.deepcopy(fixture);changed['referenceSolutions']['python:FUNCTION']+='\n# changed after preflight'
        answer=post('/validations',{'workerId':worker,'revision':2,'privateProblem':changed})
        assert answer.get('errorKey')=='EDIT_CONFLICT';report['changedPythonDenied']=True
        result=validate(2,fixture)
        assert result['runtimeValidated'] and len(result['combinations'])==6 and result['oracleValidated']
        # reusedCombinations means an already-compared whole combination; Python
        # program evidence still undergoes fresh comparisons and uses its own flag.
        assert report['steps'][1]['checkpointCount']==5 and result['pythonEvidenceReused'] is True
        report['sameTaskEvidenceReused']=True;report['passed']=True
    finally:
        pending=int(mysql("SELECT COUNT(*) FROM private_validation_job WHERE taskId='"+task+"' AND status IN ('QUEUED','RUNNING')"))
        if pending==0:
            for table in ('ai_model_call','ai_usage','ai_outbox'):assert mysql("SELECT COUNT(*) FROM "+table+" WHERE taskId='"+task+"'")=='0'
            assert mysql("SELECT COUNT(*) FROM ai_task WHERE id='"+task+"' AND ownerUserId IS NULL AND resourceId IS NULL")=='1'
            mysql("START TRANSACTION;DELETE c FROM private_validation_checkpoint c JOIN private_validation_job v ON v.id=c.validationId WHERE v.taskId='"+task+"';DELETE FROM private_validation_job WHERE taskId='"+task+"';DELETE FROM ai_task WHERE id='"+task+"' AND ownerUserId IS NULL;COMMIT;")
            report['cleanupComplete']=True
        else:report['cleanupDeferred']=True
        output.write_text(json.dumps(report,indent=2))
    print(json.dumps({'passed':report.get('passed'),'partialPublicationDenied':report.get('partialPublicationDenied'),'sameTaskEvidenceReused':report.get('sameTaskEvidenceReused'),'cleanupComplete':report.get('cleanupComplete')}),flush=True)


if __name__=='__main__':main(sys.argv[1])
