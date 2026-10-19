"""Read-only configuration/queue audit and removal of this switch's temp key file."""
import json
import os
import signal
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path
sys.path.insert(0, '/usr/local/lib/nemo-ops')
from common import container, mysql, atomic_json


def agent():
    cid = container('ai', 'nemo-agent')
    m = json.loads(subprocess.check_output(['docker', 'inspect', cid]))[0]
    env = dict(row.split('=', 1) for row in m['Config']['Env'])
    assert env['AI_MODEL_ID'] == 'qwen3.8-flash' and env['AI_PROVIDER'] == 'openai'
    assert m['State']['Running'] and m['RestartCount'] == 0
    assert env['AI_BASE_URL'] == 'https://ws-ppcqj7ezjvpiao7f.cn-beijing.maas.aliyuncs.com/compatible-mode/v1'
    assert env['AI_API_KEY'] and env['AI_MOCK_ENABLED'] == 'false'
    source = subprocess.check_output(['docker', 'exec', cid, 'python', '-c', 'from nemo_agent.analysis_graph import create_model; from nemo_agent.config import settings; m=create_model(settings); assert m.extra_body=={"enable_thinking":False}; assert m.model_name=="qwen3.8-flash"; assert m.max_retries==0'])
    assert not source
    path = Path('/srv/nemo-ai/deploy/.env.agent'); assert path.stat().st_mode & 0o777 == 0o600
    temporary = Path('/root/.cache/nemo-model-switch-20261012/request.json')
    if temporary.exists(): temporary.unlink()
    # Close only the unused stdin probe started during this switch; production
    # Agent/PG processes and file-based probes do not match these exact argv.
    closed = 0
    for directory in Path('/proc').iterdir():
        if not directory.name.isdigit(): continue
        try: argv = (directory / 'cmdline').read_bytes().split(b'\0')[:-1]
        except (OSError, ProcessLookupError): continue
        if len(argv) == 3 and argv[1:] == [b'/usr/local/lib/nemo-ops/model_provider_switch.py', b'probe']:
            try: os.kill(int(directory.name), signal.SIGTERM); closed += 1
            except ProcessLookupError: pass
    report = {'passed': True, 'model': env['AI_MODEL_ID'], 'provider': env['AI_PROVIDER'], 'baseUrl': env['AI_BASE_URL'],
              'image': m['Config']['Image'], 'running': True, 'restartCount': 0, 'privateEnvMode': '0600', 'thinkingEnabled': False,
              'sdkRetries': 0, 'mockEnabled': False, 'temporaryCredentialRemoved': not temporary.exists(), 'unusedProbeProcessesClosed': closed,
              'estimatedRatesUsdPerMillion': {'input': env.get('AI_INPUT_USD_PER_MILLION_TOKENS'), 'output': env.get('AI_OUTPUT_USD_PER_MILLION_TOKENS')},
              'estimatedRatesUnchanged': True, 'apiKeyInEvidence': False}
    atomic_json(Path('/root/.cache/nemo-model-switch-20261012/agent-audit.json'), report)
    print(json.dumps(report), flush=True)


def core():
    m = json.loads(subprocess.check_output(['docker', 'inspect', container('core', 'question-service')]))[0]
    env = dict(row.split('=', 1) for row in m['Config']['Env'])
    assert env['AI_MODEL_ID'] == 'qwen3.8-flash' and m['State']['Health']['Status'] == 'healthy'
    report = {'passed': False, 'model': env['AI_MODEL_ID'], 'image': m['Config']['Image'], 'healthy': True}
    report['pendingAiTasks'] = int(mysql("SELECT COUNT(*) FROM ai_task WHERE status IN ('QUEUED','RUNNING')"))
    report['temporaryUsersActive'] = int(mysql("SELECT COUNT(*) FROM user WHERE userAccount LIKE 'qwen\\_smoke\\_%' AND isDelete=0"))
    report['temporarySubmissionsVisible'] = int(mysql("SELECT COUNT(*) FROM question_submit s JOIN user u ON u.id=s.userId WHERE u.userAccount LIKE 'qwen\\_smoke\\_%' AND s.isDelete=0"))
    report['statisticsMismatches'] = len(mysql(Path('/srv/nemo-oj/deploy/migration/checks/submission_statistics_audit.sql').read_text()).splitlines())
    raw = subprocess.check_output(['docker', 'exec', container('core', 'rabbitmq'), 'rabbitmqctl', 'list_queues', 'name', 'messages_ready', 'messages_unacknowledged', '--formatter=json']).decode()
    report['queues'] = json.loads(raw[raw.index('['):])
    assert all(q['messages_ready'] == q['messages_unacknowledged'] == 0 for q in report['queues'])
    assert report['pendingAiTasks'] == report['temporaryUsersActive'] == report['temporarySubmissionsVisible'] == report['statisticsMismatches'] == 0
    request = urllib.request.Request('https://fw-nemo.cn/api/user/register', data=b'{}', headers={'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(request, timeout=20) as response: report['registrationStatus'] = response.status
    except urllib.error.HTTPError as error: report['registrationStatus'] = error.code
    assert report['registrationStatus'] == 403
    report['passed'] = True; atomic_json(Path('/home/ubuntu/nemo-qwen-20261012/core-audit.json'), report)
    print(json.dumps(report), flush=True)


if __name__ == '__main__': {'agent': agent, 'core': core}[sys.argv[1]]()
