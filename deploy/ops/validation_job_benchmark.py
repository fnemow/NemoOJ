"""Durable authored validation benchmark. No user, model, outbox or published problem."""
import hashlib
import json
import sys
import time
import uuid
from pathlib import Path
sys.path.insert(0,'/usr/local/lib/nemo-ops')
from common import mysql as raw

def sql(q):return raw('SET NAMES utf8mb4;\n'+q)

def main(output):
    assert sql("SELECT COUNT(*) FROM ai_task WHERE status IN ('QUEUED','RUNNING')")=='0'
    assert sql("SELECT COUNT(*) FROM question_submit WHERE status IN (0,1) AND isDelete=0")=='0'
    d=json.loads(Path('/home/ubuntu/nemo-generation-20261011/fixtures.json').read_text())['prefix_matrix']
    encoded=json.dumps(d,ensure_ascii=False,separators=(',',':')).encode(); digest=hashlib.sha256(encoded).hexdigest()
    rows=[]
    for index in range(2):
        task=str(uuid.uuid4());job=str(uuid.uuid4());start=time.monotonic()
        sql("START TRANSACTION;INSERT INTO ai_task(id,type,idempotencyKey,status,stage,startedAt,leaseOwner,leaseExpireAt) VALUES ('"+task+"','PRIVATE_PROBLEM','"+task+"','RUNNING','BENCHMARK',NOW(),'transport-benchmark',DATE_ADD(NOW(),INTERVAL 15 MINUTE));INSERT INTO private_validation_job(id,taskId,revision,draftHash,draftJson) VALUES ('"+job+"','"+task+"',1,'"+digest+"',CONVERT(UNHEX('"+encoded.hex()+"') USING utf8mb4));COMMIT;")
        try:
            deadline=time.monotonic()+840
            while True:
                state=sql("SELECT status FROM private_validation_job WHERE id='"+job+"'")
                if state in ('SUCCEEDED','FAILED','CANCELLED'):break
                assert time.monotonic()<deadline,'Validation deadline exceeded'
                time.sleep(2)
            proof=json.loads(bytes.fromhex(sql("SELECT HEX(resultJson) FROM private_validation_job WHERE id='"+job+"'")).decode())
            row={k:proof.get(k) for k in ('passed','category','combinationMillis','answerDerivationMillis','propertiesValidated','pythonEvidenceReused')}
            row.update(taskId=task,jobId=job,seconds=round(time.monotonic()-start,3),combinations=len(proof.get('combinations',[])),attemptCount=int(sql("SELECT attemptCount FROM private_validation_job WHERE id='"+job+"'")))
            rows.append(row);Path(output).write_text(json.dumps(rows,indent=2));print(json.dumps(row),flush=True)
            assert row['passed'] and row['combinations']==6 and row['propertiesValidated']
        finally:
            sql("START TRANSACTION;UPDATE ai_task SET status='SUCCEEDED',completedAt=NOW(),leaseOwner=NULL,leaseExpireAt=NULL WHERE id='"+task+"';UPDATE private_validation_job SET status='CANCELLED',completedAt=NOW(),leaseToken=NULL,leaseExpireAt=NULL WHERE id='"+job+"' AND status IN ('QUEUED','RUNNING');DELETE FROM private_validation_checkpoint WHERE validationId='"+job+"';COMMIT;")

if __name__=='__main__':main(sys.argv[1])
