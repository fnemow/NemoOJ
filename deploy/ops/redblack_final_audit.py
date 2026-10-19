"""Read-only final audit, with redacted evidence export for this release only."""
import hashlib,json,subprocess,sys
from pathlib import Path
sys.path.insert(0,'/usr/local/lib/nemo-ops');from common import mysql,atomic_json
from monitor import collect
stage=Path('/home/ubuntu/nemo-redblack-site-20261014')
sql="""SELECT COUNT(*) FROM
(SELECT questionId FROM question_submission_stats UNION SELECT questionId FROM question_submit WHERE questionId IS NOT NULL) ids
LEFT JOIN question_submission_stats s ON s.questionId=ids.questionId
LEFT JOIN (SELECT questionId,COUNT(*) AS submitNum,SUM(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(judgeInfo,'$.message'))='accepted',0)) AS acceptedNum
FROM question_submit WHERE isDelete=0 GROUP BY questionId) a ON a.questionId=ids.questionId
WHERE COALESCE(s.submitNum,0)<>COALESCE(a.submitNum,0) OR COALESCE(s.acceptedNum,0)<>COALESCE(a.acceptedNum,0)"""
def run(args):return subprocess.check_output(args,stderr=subprocess.PIPE).decode()
containers=json.loads(run(['docker','inspect',*('nemo-oj-'+s+'-1' for s in ('gateway','user-service','question-service','judge-service','sandbox','proxy'))]))
queues=run(['docker','exec','nemo-oj-rabbitmq-1','rabbitmqctl','list_queues','name','messages_ready','messages_unacknowledged','--no-table-headers'])
queue_rows=[]
for line in queues.splitlines():
    parts=line.split('\t')
    if len(parts)==3 and parts[1].isdigit() and parts[2].isdigit():queue_rows.append({'name':parts[0],'ready':int(parts[1]),'unacknowledged':int(parts[2])})
monitor=collect('core')
report={'monitorIssues':monitor['issues'],'availableMemoryMiB':monitor['availableMemoryMiB'],
        'activeAiTasks':int(mysql("SELECT COUNT(*) FROM ai_task WHERE status IN ('QUEUED','RUNNING')")),
        'activeSubmissions':int(mysql('SELECT COUNT(*) FROM question_submit WHERE isDelete=0 AND status IN (0,1)')),
        'liveAcceptanceAccounts':int(mysql("SELECT COUNT(*) FROM user WHERE (userAccount LIKE 'rb_eval_%' OR userAccount LIKE 'rbformal_%') AND isDelete=0")),
        'publicProblems':int(mysql("SELECT COUNT(*) FROM question_public_number WHERE status='ONLINE'")),
        'statisticsMismatches':int(mysql(sql)),
        'sandboxContainerResidues':len(run(['docker','ps','-aq','--filter','label=nemo-oj.sandbox=true']).split()),
        'indexSha256':hashlib.sha256(Path('/srv/nemo-oj/nemo-frontend/dist/index.html').read_bytes()).hexdigest(),
        'services':[{'name':c['Name'],'running':c['State']['Running'],'health':c['State'].get('Health',{}).get('Status'),'startedAt':c['State']['StartedAt']} for c in containers],
        'queues':queue_rows,'registrationEnabled':any(line=='REGISTRATION_ENABLED=true' for line in Path('/srv/nemo-oj/deploy/.env').read_text().splitlines())}
atomic_json(stage/'final-audit.json',report)
print(json.dumps(report),flush=True)
