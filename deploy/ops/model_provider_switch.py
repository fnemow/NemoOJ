"""Host-only model switch. Credentials arrive on stdin/private file, never argv/logs."""
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path


def metadata(service):
    project = 'nemo-ai' if service == 'nemo-agent' else 'nemo-oj'
    ids = subprocess.check_output(['docker', 'ps', '-q', '--filter', 'label=com.docker.compose.project=' + project,
                                   '--filter', 'label=com.docker.compose.service=' + service]).decode().split()
    if not ids:
        ids = subprocess.check_output(['docker', 'ps', '-aq', '--filter', 'label=com.docker.compose.project=' + project,
                                      '--filter', 'label=com.docker.compose.service=' + service]).decode().split()
    assert len(ids) == 1, 'Expected exactly one running service'
    return json.loads(subprocess.check_output(['docker', 'inspect', ids[0]]))[0]


def patch_env(original, updates):
    lines = original.splitlines(); found = set()
    for i, line in enumerate(lines):
        if '=' in line and not line.lstrip().startswith('#'):
            key = line.split('=', 1)[0].strip()
            if key in updates:
                lines[i] = key + '=' + updates[key]; found.add(key)
    lines.extend(key + '=' + value for key, value in updates.items() if key not in found)
    return '\n'.join(lines) + '\n'


def write_private(path, text):
    fd, temporary = tempfile.mkstemp(prefix='.model-switch-', dir=path.parent)
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, 'w') as f: f.write(text); f.flush(); os.fsync(f.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary): os.unlink(temporary)


def probe(payload):
    # Synthetic input only; validate the same forced tool-calling protocol as LangChain.
    body = {'model': payload['model'], 'messages': [{'role': 'user', 'content': 'Call connection_check with ok=true. No other text.'}],
            'max_tokens': 256, 'temperature': 0, 'enable_thinking': False,
            'tools': [{'type': 'function', 'function': {'name': 'connection_check', 'description': 'Confirm API connectivity.',
                     'parameters': {'type': 'object', 'properties': {'ok': {'type': 'boolean'}}, 'required': ['ok']}}}],
            'tool_choice': {'type': 'function', 'function': {'name': 'connection_check'}}}
    started = time.monotonic()
    request = urllib.request.Request(payload['baseUrl'].rstrip('/') + '/chat/completions',
            data=json.dumps(body).encode(), headers={'Authorization': 'Bearer ' + payload['apiKey'], 'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(request, timeout=60) as response: result = json.load(response)
    except urllib.error.HTTPError as error:
        # Provider messages may include credentials or request data; print only code/type.
        try: detail = json.load(error).get('error', {})
        except Exception: detail = {}
        print(json.dumps({'passed': False, 'httpStatus': error.code, 'providerErrorCode': str(detail.get('code', ''))[:100],
                          'providerErrorType': str(detail.get('type', ''))[:100],
                          'providerMessage': str(detail.get('message', '')).replace(payload['apiKey'], '[REDACTED]')[:350]}), flush=True)
        raise RuntimeError('Provider probe rejected') from None
    calls = result['choices'][0]['message'].get('tool_calls') or []
    assert len(calls) == 1 and calls[0]['function']['name'] == 'connection_check'
    assert json.loads(calls[0]['function']['arguments'])['ok'] is True
    print(json.dumps({'passed': True, 'modelRequested': payload['model'], 'modelReturned': result.get('model'),
                      'forcedToolCalling': True, 'seconds': round(time.monotonic() - started, 3), 'usage': result.get('usage', {})}), flush=True)


def apply(service, payload):
    meta = metadata(service); labels = meta['Config']['Labels']
    directory = Path(labels['com.docker.compose.project.working_dir'])
    path = directory / ('.env.agent' if service == 'nemo-agent' else '.env')
    original = path.read_text()
    updates = {'AI_MODEL_PROVIDER': 'openai', 'AI_MODEL_ID': payload['model']}
    if service == 'nemo-agent': updates.update(AI_MODEL_BASE_URL=payload['baseUrl'], AI_MODEL_API_KEY=payload['apiKey'])
    if service == 'nemo-agent' and payload.get('image'):
        assert payload['image'] == 'nemo-oj/agent:qwen-compatible-20261012'
        updates['NEMO_AGENT_IMAGE'] = payload['image']
    assert '\n' not in ''.join(updates.values()) and '\r' not in ''.join(updates.values())
    files = list(dict.fromkeys(labels['com.docker.compose.project.config_files'].split(',')))
    command = ['docker', 'compose', '--env-file', str(path)]
    for file in files: command.extend(['-f', file])
    command.extend(['up', '-d', '--no-deps', service])
    write_private(path, patch_env(original, updates))
    try:
        completed = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=180)
        assert completed.returncode == 0, 'Service recreation failed; command output withheld'
        deadline = time.monotonic() + 150
        while time.monotonic() < deadline:
            new = metadata(service); state = new['State']
            if state['Running'] and (service == 'nemo-agent' or state.get('Health', {}).get('Status') == 'healthy'):
                if service == 'nemo-agent':
                    time.sleep(8)
                    new = metadata(service)
                    assert new['RestartCount'] == 0 and new['State']['Running']
                actual = dict(e.split('=', 1) for e in new['Config']['Env'])
                assert actual['AI_MODEL_ID'] == payload['model']
                if service == 'nemo-agent': assert actual['AI_API_KEY'] == payload['apiKey'] and actual['AI_BASE_URL'] == payload['baseUrl']
                print(json.dumps({'passed': True, 'service': service, 'model': actual['AI_MODEL_ID'], 'image': new['Config']['Image'],
                                  'privateEnvMode': oct(path.stat().st_mode & 0o777), 'secretsLogged': False}), flush=True)
                return
            time.sleep(3)
        raise RuntimeError('Service readiness deadline exceeded')
    except Exception:
        write_private(path, original)
        rollback = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=180)
        print(json.dumps({'rolledBack': rollback.returncode == 0, 'service': service}), flush=True)
        raise


if __name__ == '__main__':
    mode = sys.argv[1]
    if len(sys.argv) == 3:
        path = Path(sys.argv[2])
        if mode in ('probe', 'agent'):
            assert path.resolve() == Path('/root/.cache/nemo-model-switch-20261012/request.json')
            assert path.stat().st_mode & 0o077 == 0 and path.stat().st_uid == 0
        payload = json.loads(path.read_text())
    else:
        print('Waiting for private configuration on stdin', flush=True)
        payload = json.loads(sys.stdin.readline())
    assert payload['model'] == 'qwen3.8-flash'
    if mode in ('probe', 'agent'):
        assert payload['baseUrl'] == 'https://ws-ppcqj7ezjvpiao7f.cn-beijing.maas.aliyuncs.com/compatible-mode/v1'
        assert payload['apiKey'].startswith('sk-')
    if mode == 'probe': probe(payload)
    elif mode in ('agent', 'core'): apply('nemo-agent' if mode == 'agent' else 'question-service', payload)
    else: raise ValueError('Unsupported operation')
