"""Delete only unowned synthetic benchmark metadata after terminal states."""
import hashlib
import json
import re
import sys
from pathlib import Path
sys.path.insert(0,'/usr/local/lib/nemo-ops')
from common import mysql

def main():
    d=json.loads(Path('/home/ubuntu/nemo-generation-20261011/fixtures.json').read_text())['prefix_matrix']
    digest=hashlib.sha256(json.dumps(d,ensure_ascii=False,separators=(',',':')).encode()).hexdigest()
    rows=mysql("SELECT t.id,v.id FROM ai_task t JOIN private_validation_job v ON v.taskId=t.id WHERE t.ownerUserId IS NULL AND t.resourceId IS NULL AND t.type='PRIVATE_PROBLEM' AND t.idempotencyKey=t.id AND t.status='SUCCEEDED' AND v.status='SUCCEEDED' AND v.draftHash='"+digest+"' AND t.createTime>='2026-10-13 00:00:00'").splitlines()
    assert len(rows)==8, 'Unexpected synthetic benchmark set'
    tasks=[];jobs=[]
    for row in rows:
        tid,jid=row.split('\t');assert re.fullmatch('[a-f0-9-]{36}',tid) and re.fullmatch('[a-f0-9-]{36}',jid)
        tasks.append("'"+tid+"'");jobs.append("'"+jid+"'")
    task_ids=','.join(tasks);job_ids=','.join(jobs)
    for table in ('ai_model_call','ai_usage','ai_outbox'):
        assert mysql('SELECT COUNT(*) FROM '+table+' WHERE taskId IN ('+task_ids+')')=='0'
    mysql('START TRANSACTION;DELETE FROM private_validation_checkpoint WHERE validationId IN ('+job_ids+');DELETE FROM private_validation_job WHERE id IN ('+job_ids+');DELETE FROM ai_task WHERE id IN ('+task_ids+');COMMIT;')
    print(json.dumps({'removedSyntheticTasks':8,'modelCalls':0,'realUserDataChanged':False}),flush=True)

if __name__=='__main__':main()
