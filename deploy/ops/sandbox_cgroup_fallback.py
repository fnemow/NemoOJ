"""Temporary sandbox service without the host mount exercises the Docker-exec fallback.
Secrets remain in server process environment; no public ports, model or database writes.
"""
import json
import os
import subprocess
import sys
import time
import urllib.request
import uuid
from pathlib import Path


def run(args, env=None):
    return subprocess.check_output(['/usr/bin/docker', *args], env=env, stderr=subprocess.DEVNULL, timeout=90)


def main(output):
    original = json.loads(run(['inspect', 'nemo-oj-sandbox-1']))[0]
    values = dict(v.split('=', 1) for v in original['Config']['Env'])
    values['SANDBOX_DOCKER_HOST_CGROUP_ROOT'] = '/missing-for-fallback'
    network = next(iter(original['NetworkSettings']['Networks']))
    name = 'nemo-cgroup-fallback-' + uuid.uuid4().hex[:12]
    report = {'cases': [], 'publicPorts': False}
    args = ['create', '--name', name, '--network', network, '--memory', '512m',
            '--user', '0:0', '--workdir', original['Config']['WorkingDir'],
            '--volume', '/var/lib/nemo-oj/sandbox:/var/lib/nemo-oj/sandbox',
            '--volume', '/var/run/docker.sock:/var/run/docker.sock']
    for key in values:
        args += ['--env', key]  # Values never appear in command arguments or logs.
    container = None
    try:
        container = run(args + [original['Config']['Image']], env={**os.environ, **values}).decode().strip()
        run(['start', container])
        meta = json.loads(run(['inspect', container]))[0]
        ip = meta['NetworkSettings']['Networks'][network]['IPAddress']
        management = 'http://' + ip + ':' + values.get('MANAGEMENT_PORT', '9190')
        deadline = time.monotonic() + 150
        while time.monotonic() < deadline:
            try:
                with urllib.request.urlopen(management + '/actuator/health/readiness', timeout=3) as r:
                    if json.load(r).get('status') == 'UP':
                        break
            except Exception:
                pass
            time.sleep(3)
        else:
            raise RuntimeError('Fallback service readiness timed out')
        base = 'http://' + ip + ':' + values.get('SERVER_PORT', '8090') + '/api'
        headers = {'Content-Type': 'application/json', 'auth': values['SANDBOX_SECRET']}

        def call(path, data=None):
            request = urllib.request.Request(base + path, data=None if data is None else json.dumps(data).encode(), headers=headers)
            with urllib.request.urlopen(request, timeout=50) as r:
                return json.load(r)

        runtime = next(r['runtimeId'] for r in call('/v2/capabilities') if r['language'] == 'python' and r['imageReady'])
        for label, code, expected in [('AC', 'print(42)', 0), ('MLE', 'a=bytearray(512*1024*1024)', 4)]:
            plan = {'runtimeId': runtime, 'language': 'python', 'mode': 'ACM', 'entryPoint': 'main.py',
                    'sourceFiles': [{'fileName': 'main.py', 'content': code}], 'cases': [{'caseIndex': 0, 'stdin': ''}],
                    'limits': {'timeLimitMs': 1000, 'memoryLimitKB': 262144, 'outputLimitBytes': 4096}}
            result = call('/v2/executeCode', {'submissionId': 1, 'backgroundValidation': True, 'executionPlan': plan})['cases'][0]
            assert result['status'] == expected and result['memory'] > 0
            report['cases'].append({'case': label, 'status': result['status'], 'memory': result['memory']})
        with urllib.request.urlopen(management + '/actuator/prometheus', timeout=5) as r:
            metrics = r.read().decode()
        assert 'stage="memory_exec_snapshot"' in metrics and 'stage="oom_exec_baseline"' in metrics
        assert 'stage="memory_host_snapshot"' not in metrics
        report['fallbackUsed'] = True
    finally:
        if container:
            run(['rm', '--force', container])
        report['temporaryServiceRemoved'] = True
        Path(output).write_text(json.dumps(report, indent=2))
    report['passed'] = True
    Path(output).write_text(json.dumps(report, indent=2))
    print(json.dumps(report), flush=True)


if __name__ == '__main__':
    main(sys.argv[1])
