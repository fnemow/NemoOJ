"""Read-only release audit. Reports contain counts and image names, never secrets/code."""
import json
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, '/usr/local/lib/nemo-ops')
from common import container, mysql

role = sys.argv[1]
assert role in ('core', 'ai')
stage = Path('/home/ubuntu/nemo-generation-20261011' if role == 'core' else '/root/nemo-generation-20261011')
services = ['judge-service', 'question-service', 'sandbox'] if role == 'core' else ['nemo-agent']
report = {'role': role, 'services': {}}
for service in services:
    meta = json.loads(subprocess.check_output(['docker', 'inspect', container(role, service)]))[0]
    report['services'][service] = {
        'image': meta['Config']['Image'], 'state': meta['State']['Status'],
        'health': meta['State'].get('Health', {}).get('Status'),
    }
    if role == 'ai':
        values = dict(e.split('=', 1) for e in meta['Config']['Env'])
        report['templateEnabled'] = values.get('AI_TRUSTED_TEMPLATES_ENABLED') == 'true'
        report['canonicalEnabled'] = values.get('AI_CANONICAL_GENERATION_ENABLED') == 'true'
        logs = subprocess.check_output(['docker', 'logs', '--since', meta['State']['StartedAt'], meta['Id']], stderr=subprocess.STDOUT).decode()
        report['fourQueuesStarted'] = all('consuming '+q in logs for q in (
            'nemo.ai.tasks', 'nemo.ai.analysis', 'nemo.ai.plans', 'nemo.ai.private'))
if role == 'core':
    report['mixedTestRunning'] = subprocess.run(['pgrep', '-f',
        '^python3 /home/ubuntu/nemo-generation-20261011/generation-v3-mixed.py$'],
        stdout=subprocess.PIPE).returncode == 0
    if (stage / 'mixed-load.json').exists():
        report['mixedProgress'] = json.loads((stage / 'mixed-load.json').read_text())
    report['activeAiTasks'] = int(mysql("SELECT COUNT(*) FROM ai_task WHERE status IN ('QUEUED','RUNNING')"))
    report['activeValidations'] = int(mysql("SELECT COUNT(*) FROM private_validation_job WHERE status IN ('QUEUED','RUNNING')"))
    report['activeSubmissions'] = int(mysql('SELECT COUNT(*) FROM question_submit WHERE status IN (0,1) AND isDelete=0'))
    report['publicProblemCount'] = int(mysql("SELECT COUNT(*) FROM question_public_number WHERE status='ONLINE'"))
    report['residualSandboxContainers'] = len(subprocess.check_output([
        'docker', 'ps', '-aq', '--filter', 'label=nemo-oj.sandbox=true']).decode().splitlines())
    queues = subprocess.check_output(['docker', 'exec', container('core', 'rabbitmq'),
        'rabbitmqctl', 'list_queues', 'name', 'messages_ready', 'messages_unacknowledged', 'consumers', '--formatter=json']).decode()
    report['queues'] = json.loads(queues[queues.index('['):])
    report['statisticsMismatches'] = int(mysql("""SELECT COUNT(*) FROM (
        SELECT ids.questionId FROM
        (SELECT questionId FROM question_submission_stats UNION SELECT questionId FROM question_submit WHERE questionId IS NOT NULL) ids
        LEFT JOIN question_submission_stats s ON s.questionId=ids.questionId
        LEFT JOIN (SELECT questionId,COUNT(*) AS submitNum,
          SUM(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(judgeInfo,'$.message'))='accepted',0)) AS acceptedNum
          FROM question_submit WHERE isDelete=0 GROUP BY questionId) a ON a.questionId=ids.questionId
        WHERE COALESCE(s.submitNum,0)<>COALESCE(a.submitNum,0)
          OR COALESCE(s.acceptedNum,0)<>COALESCE(a.acceptedNum,0)) mismatches"""))
    report['activeTemporaryAccounts'] = int(mysql("SELECT COUNT(*) FROM user WHERE userAccount LIKE 'gen3\\_%' AND isDelete=0"))
mem = dict(line.replace(':', '').split(None, 1) for line in Path('/proc/meminfo').read_text().splitlines())
report['hostMemoryKiB'] = {key: int(mem[key].split()[0]) for key in ('MemTotal','MemAvailable','SwapTotal','SwapFree')}
(stage / 'final-audit.json').write_text(json.dumps(report))
print(json.dumps(report), flush=True)
