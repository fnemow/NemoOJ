"""Read-only deployment/task audit. No identities, credentials or submitted code."""
import json, sys
sys.path.insert(0, '/usr/local/lib/nemo-ops')
from common import mysql

def sql(query):
    return mysql('SET NAMES utf8mb4;\n' + query)

active = int(sql("SELECT COUNT(*) FROM ai_task WHERE status IN ('QUEUED','RUNNING')"))
pending = int(sql('SELECT COUNT(*) FROM question_submit WHERE isDelete=0 AND status IN (0,1)'))
raw = sql("SELECT HEX(JSON_OBJECT('difficulty',JSON_UNQUOTE(JSON_EXTRACT(requestJson,'$.difficulty')),'status',status,'stage',stage,'errorCode',errorCode,'reason',IF(errorCode='GOAL_UNSUPPORTED',LEFT(errorMessage,260),NULL))) FROM ai_task WHERE type='PRIVATE_PROBLEM' AND requestJson LIKE '%红黑树%' ORDER BY createTime DESC LIMIT 9")
rows = [json.loads(bytes.fromhex(line).decode()) for line in raw.splitlines() if line]
print(json.dumps({'activeAiTasks':active,'activeSubmissions':pending,'recentRedBlackTasks':rows},ensure_ascii=False))
