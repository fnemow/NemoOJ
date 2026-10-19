"""Remove only the exact unowned protocol task in an operator-authored report."""
import json,sys,uuid
from pathlib import Path
sys.path.insert(0,'/usr/local/lib/nemo-ops');from common import mysql,atomic_json
path=Path(sys.argv[1]);assert path.name=='event-protocol.json';report=json.loads(path.read_text());task=report['taskId'];uuid.UUID(task)
assert mysql("SELECT COUNT(*) FROM private_validation_job WHERE taskId='"+task+"' AND status IN ('QUEUED','RUNNING')")=='0'
assert mysql("SELECT COUNT(*) FROM ai_task WHERE id='"+task+"' AND type='PRIVATE_PROBLEM' AND idempotencyKey='"+task+"' AND ownerUserId IS NULL AND resourceId IS NULL")=='1'
for table in ('ai_model_call','ai_usage'):assert mysql("SELECT COUNT(*) FROM "+table+" WHERE taskId='"+task+"'")=='0'
mysql("START TRANSACTION;DELETE c FROM private_validation_checkpoint c JOIN private_validation_job v ON v.id=c.validationId WHERE v.taskId='"+task+"';DELETE FROM private_validation_job WHERE taskId='"+task+"';DELETE FROM ai_outbox WHERE taskId='"+task+"';DELETE FROM ai_task WHERE id='"+task+"' AND ownerUserId IS NULL AND resourceId IS NULL;COMMIT;")
report['cleanupComplete']=True;atomic_json(path,report);print(json.dumps({'cleanupComplete':True,'modelCalls':0}))
