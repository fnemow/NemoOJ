"""Read-only aggregate diagnostics. Never export goals, code, users or credentials."""
import json
import sys
sys.path.insert(0, '/usr/local/lib/nemo-ops')
from common import mysql

def main():
    queries = {
        'testCleanup': "SELECT COUNT(*) FROM user WHERE userName='阶段生成验收' AND isDelete=0",
        'active': "SELECT type,COUNT(*) FROM ai_task WHERE status IN ('QUEUED','RUNNING') GROUP BY type",
        'paths': "SELECT COALESCE(JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(j.draftJson),j.draftJson,'{}'),'$.generation.protocol')),'LEGACY') AS protocol,COALESCE(JSON_UNQUOTE(JSON_EXTRACT(j.resultJson,'$.combination')),'NONE') AS combo,COALESCE(JSON_UNQUOTE(JSON_EXTRACT(j.resultJson,'$.category')),'NONE') AS category,COUNT(*) FROM private_validation_job j WHERE j.createTime>=NOW()-INTERVAL 7 DAY GROUP BY protocol,combo,category",
        'tasks': "SELECT status,COALESCE(errorCode,'NONE'),COUNT(*),ROUND(AVG(TIMESTAMPDIFF(SECOND,createTime,completedAt)),1) FROM ai_task WHERE type='PRIVATE_PROBLEM' AND createTime>=NOW()-INTERVAL 7 DAY GROUP BY status,errorCode",
        'validation': "SELECT j.status,COALESCE(JSON_UNQUOTE(JSON_EXTRACT(j.resultJson,'$.category')),'NONE') AS category,COUNT(*),ROUND(AVG(TIMESTAMPDIFF(SECOND,j.createTime,j.startedAt)),1),ROUND(AVG(TIMESTAMPDIFF(SECOND,j.startedAt,j.completedAt)),1) FROM private_validation_job j JOIN ai_task t ON t.id=j.taskId WHERE t.type='PRIVATE_PROBLEM' AND j.createTime>=NOW()-INTERVAL 7 DAY GROUP BY j.status,category",
        'usage': "SELECT c.state,COUNT(*),SUM(c.inputTokens),SUM(c.outputTokens) FROM ai_model_call c JOIN ai_task t ON t.id=c.taskId WHERE t.type='PRIVATE_PROBLEM' AND t.createTime>=NOW()-INTERVAL 7 DAY GROUP BY c.state",
    }
    result = {}
    for key, sql in queries.items():
        try: result[key] = [line.split('\t') for line in mysql(sql).splitlines()]
        except Exception as exc:
            import re
            err = getattr(exc, 'stderr', b'').decode(errors='replace')
            unknown = re.search(r"Unknown column '[A-Za-z0-9_.]+'", err)
            number = re.search(r'ERROR (\d+)', err)
            result[key] = {'error': unknown.group(0) if unknown else 'SQL_FAILED', 'mysqlCode':number.group(1) if number else None}
    print(json.dumps(result, ensure_ascii=False))

if __name__ == '__main__': main()
