"""Durable fixed corpus evaluation. Platform sources only, no model/user/outbox/publication."""
import hashlib
import json
import sys
import time
import uuid
from pathlib import Path
sys.path.insert(0, '/usr/local/lib/nemo-ops')
from common import mysql


def sql(query): return mysql('SET NAMES utf8mb4;\n' + query)


def main(stage):
    stage=Path(stage);fixtures=json.loads((stage/'fixtures.json').read_text());report={'results':[],'modelCalls':0,'cleanup':[]}
    output=stage/'evaluation.json'
    for key,draft in fixtures.items():
        task=str(uuid.uuid4());job=str(uuid.uuid4())
        raw=json.dumps(draft,ensure_ascii=False,separators=(',',':')).encode();digest=hashlib.sha256(raw).hexdigest()
        row={'template':key,'taskId':task,'validationId':job,'draftHash':digest}
        report['results'].append(row);output.write_text(json.dumps(report,indent=2))
        sql("START TRANSACTION;INSERT INTO ai_task(id,type,idempotencyKey,status,stage,startedAt,leaseOwner,leaseExpireAt) VALUES ('"+task+"','PRIVATE_PROBLEM','"+task+"','RUNNING','TEMPLATE_EVALUATION',NOW(),'template-evaluation',DATE_ADD(NOW(),INTERVAL 15 MINUTE));INSERT INTO private_validation_job(id,taskId,revision,draftHash,draftJson) VALUES ('"+job+"','"+task+"',1,'"+digest+"',CONVERT(UNHEX('"+raw.hex()+"') USING utf8mb4));COMMIT;")
        start=time.monotonic();terminal=False
        try:
            deadline=start+840
            while True:
                status=sql("SELECT status FROM private_validation_job WHERE id='"+job+"'")
                if status in ('SUCCEEDED','FAILED','CANCELLED'):
                    terminal=True;break
                if time.monotonic()>deadline: raise TimeoutError('Fixed evaluation deadline')
                time.sleep(2)
            encoded=sql("SELECT COALESCE(HEX(resultJson),'') FROM private_validation_job WHERE id='"+job+"'")
            proof=json.loads(bytes.fromhex(encoded)) if encoded else {}
            row.update(status=status,seconds=round(time.monotonic()-start,3),passed=bool(proof.get('passed')),
                       category=proof.get('category'),combinations=len(proof.get('combinations',[])),
                       oracleValidated=proof.get('oracleValidated'),propertiesValidated=proof.get('propertiesValidated'),
                       attemptCount=int(sql("SELECT attemptCount FROM private_validation_job WHERE id='"+job+"'")),
                       checkpointCount=int(sql("SELECT COUNT(*) FROM private_validation_checkpoint WHERE validationId='"+job+"'")))
            print(json.dumps(row),flush=True)
            output.write_text(json.dumps(report,indent=2))
        finally:
            if terminal:
                for table in ('ai_model_call','ai_usage','ai_outbox'):
                    assert sql("SELECT COUNT(*) FROM "+table+" WHERE taskId='"+task+"'")=='0'
                assert sql("SELECT COUNT(*) FROM ai_task WHERE id='"+task+"' AND ownerUserId IS NULL AND resourceId IS NULL AND idempotencyKey=id")=='1'
                sql("START TRANSACTION;DELETE FROM private_validation_checkpoint WHERE validationId='"+job+"';DELETE FROM private_validation_job WHERE id='"+job+"' AND taskId='"+task+"' AND status IN ('SUCCEEDED','FAILED','CANCELLED');DELETE FROM ai_task WHERE id='"+task+"' AND ownerUserId IS NULL;COMMIT;")
                report['cleanup'].append(task)
                output.write_text(json.dumps(report,indent=2))
        assert row['passed'] and row['combinations']==6 and row['oracleValidated'] and row['propertiesValidated'], 'Fixed template gate failed: '+key
    report['passed']=len(report['results'])==12 and all(r['passed'] for r in report['results'])
    output.write_text(json.dumps(report,indent=2));print(json.dumps({'passed':report['passed'],'contracts':len(report['results']),'modelCalls':0}),flush=True)


if __name__=='__main__': main(sys.argv[1])
