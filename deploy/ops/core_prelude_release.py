"""Native acceptance and minimal release for import-free FUNCTION templates."""
import copy
import hashlib
import io
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

sys.path.insert(0, '/usr/local/lib/nemo-ops')
from common import atomic_json, container, mysql
from catalog_306_release import Api, old_fingerprint


def run(args):
    return subprocess.check_output(args, stderr=subprocess.PIPE).decode()


def probe(stage):
    fixtures = json.loads((stage / 'native-fixtures.json').read_text())
    meta = json.loads(run(['docker', 'inspect', container('core', 'sandbox')]))[0]
    ip = next(v['IPAddress'] for v in meta['NetworkSettings']['Networks'].values() if v['IPAddress'])
    env = dict(v.split('=', 1) for v in meta['Config']['Env'])
    report = {'passed': False, 'checks': []}
    for index, fixture in enumerate(fixtures):
        request = urllib.request.Request('http://' + ip + ':8090/api/v2/executeCode',
            data=json.dumps({'submissionId': 0, 'backgroundValidation': True, 'executionPlan': fixture['executionPlan']}).encode(),
            headers={'Content-Type': 'application/json', 'auth': env['SANDBOX_SECRET']})
        deadline = time.monotonic() + 120
        while True:
            try:
                with urllib.request.urlopen(request, timeout=80) as response:
                    result = json.load(response)
                break
            except urllib.error.HTTPError as error:
                if error.code != 503 or time.monotonic() > deadline: raise
                time.sleep(5)
        status = fixture['status']
        if status == 'COMPILE_ERROR':
            assert result.get('compileMessage') and fixture['location'] in result['compileMessage'], (index, result)
        else:
            assert result['overallStatus'] == 0 and len(result['cases']) == 1, (index, result)
            case = result['cases'][0]
            assert case['exitCode'] == 0, (index, case)
            wire = json.loads(case['stdout'])
            assert wire['status'] == status, (index, wire)
            if status == 'OK': assert wire['actual'] == [1, 3, 3]
            else: assert fixture['location'] in wire['error']
        report['checks'].append({'index': index, 'language': fixture['language'], 'split': fixture['split'], 'expected': status, 'passed': True})
        atomic_json(stage / 'native-report.json', report)
        print(json.dumps(report['checks'][-1]), flush=True)
    report['passed'] = len(report['checks']) == 12
    atomic_json(stage / 'native-report.json', report)


def deploy(stage):
    assert json.loads((stage / 'native-report.json').read_text())['passed']
    assert int(mysql('SELECT COUNT(*) FROM question_submit WHERE isDelete=0 AND status IN (0,1)')) == 0
    assert int(mysql("SELECT COUNT(*) FROM private_validation_job WHERE status IN ('QUEUED','RUNNING')")) == 0
    replacements = json.loads((stage / 'patch-manifest.json').read_text())
    report = {'passed': False, 'services': {}, 'old166Fingerprint': old_fingerprint()}
    compose = None
    for service, entries in replacements.items():
        meta = json.loads(run(['docker', 'inspect', container('core', service)]))[0]
        original = subprocess.check_output(['docker', 'exec', meta['Id'], 'cat', '/app/service.jar'])
        changes = {}
        for entry in entries:
            payload = (stage / entry['path']).read_bytes()
            assert hashlib.sha256(payload).hexdigest() == entry['sha256']
            changes[entry['name']] = payload
        output = io.BytesIO()
        with zipfile.ZipFile(io.BytesIO(original)) as before, zipfile.ZipFile(output, 'w') as after:
            for item in before.infolist():
                after.writestr(copy.copy(item), changes.get(item.filename, before.read(item)))
            added = set(changes) - set(before.namelist())
            assert added <= {'BOOT-INF/classes/com/nemo/OJbackendjudgeservice/judge/harness/FunctionCodePrelude.class'}
            for name in added: after.writestr(name, changes[name])
        with zipfile.ZipFile(io.BytesIO(original)) as before, zipfile.ZipFile(io.BytesIO(output.getvalue())) as after:
            assert set(after.namelist()) == set(before.namelist()) | set(changes)
            assert all(before.read(name) == after.read(name) for name in before.namelist() if name not in changes)
            assert all(after.read(name) == data for name, data in changes.items())
        image = 'nemo-oj/' + service + ':core-prelude-20261011'
        folder = stage / service; folder.mkdir(exist_ok=True)
        (folder / 'service.jar').write_bytes(output.getvalue())
        (folder / 'Dockerfile').write_text('FROM ' + meta['Config']['Image'] + '\nCOPY service.jar /app/service.jar\n')
        run(['docker', 'build', '--network=none', '--pull=false', '-t', image, str(folder)])
        report['services'][service] = {'image': image, 'previousImage': meta['Config']['Image'], 'changedEntries': list(changes), 'otherEntriesUnchanged': True}
        if compose is None:
            labels = meta['Config']['Labels']; envfile = Path(labels['com.docker.compose.project.environment_file'])
            assert envfile == Path('/srv/nemo-oj/deploy/.env')
            compose = ['docker', 'compose', '--project-name', labels['com.docker.compose.project'], '--env-file', str(envfile)]
            for file in labels['com.docker.compose.project.config_files'].split(','):
                assert Path(file).parent == envfile.parent
                compose += ['-f', file]
    original_env = envfile.read_text(); assert 'REGISTRATION_ENABLED=false' in original_env.splitlines()
    keys = {'judge-service': 'NEMO_JUDGE_IMAGE', 'question-service': 'NEMO_QUESTION_IMAGE'}
    new_env = '\n'.join(line for line in original_env.splitlines() if not any(line.startswith(key + '=') for key in keys.values())) + '\n'
    new_env += ''.join(keys[s] + '=' + info['image'] + '\n' for s, info in report['services'].items())
    original_config = json.loads(run(compose + ['config', '--format', 'json']))
    def write(text):
        fd, name = tempfile.mkstemp(dir=envfile.parent, prefix='.prelude-')
        os.fchmod(fd, envfile.stat().st_mode & 0o777)
        with os.fdopen(fd, 'w') as stream: stream.write(text); stream.flush(); os.fsync(stream.fileno())
        os.replace(name, envfile)
    try:
        write(new_env)
        actual = json.loads(run(compose + ['config', '--format', 'json']))
        for service in keys:
            actual['services'][service]['image'] = original_config['services'][service]['image']
        assert actual == original_config
        # New judge accepts both old and new templates. Publish template API only after readiness.
        for service in ('judge-service', 'question-service'):
            run(compose + ['up', '-d', '--no-deps', '--pull', 'never', service])
            deadline = time.monotonic() + 180
            while time.monotonic() < deadline:
                meta = json.loads(run(['docker', 'inspect', container('core', service)]))[0]
                if meta['State'].get('Health', {}).get('Status') == 'healthy': break
                time.sleep(3)
            else: raise RuntimeError(service + ' readiness timeout')
            print(json.dumps({'service': service, 'healthy': True}), flush=True)
    except Exception:
        write(original_env)
        run(compose + ['up', '-d', '--no-deps', '--pull', 'never', 'judge-service', 'question-service'])
        raise
    report.update(passed=True, backupsCreated=False, otherServicesRestarted=False)
    atomic_json(stage / 'release-report.json', report)


def sweep(stage):
    api = Api(); report = {'passed': False, 'details': 0, 'templates': 0}
    for number in range(1, 307):
        q = api.call('/question/get/vo/by-number?problemNo=' + str(number))
        for language in ('java', 'python', 'cpp'):
            for mode in ('ACM', 'FUNCTION'):
                query = urllib.parse.urlencode({'questionId': q['id'], 'language': language, 'executionMode': mode, 'problemVersion': q['problemVersion']})
                t = api.call('/question/code_template?' + query)
                assert t['code'].strip() and t['mode'] == mode
                if mode == 'FUNCTION':
                    assert t['code'].startswith('class Solution') and q['problemSpec']['methodName'] in t['code']
                    assert '#include' not in t['code'] and 'import ' not in t['code'] and 'using namespace' not in t['code']
                    assert t['templateVersion'].endswith('-v2')
                elif language == 'cpp': assert '#include <iostream>' in t['code'] and 'using namespace std;' in t['code']
                report['templates'] += 1
        report['details'] += 1
        if number % 50 == 0: print(json.dumps(report), flush=True)
    report.update(passed=True, old166Unchanged=old_fingerprint() == json.loads((stage / 'release-report.json').read_text())['old166Fingerprint'])
    assert report['old166Unchanged']
    atomic_json(stage / 'template-report.json', report)


def audit(stage):
    names = ('native-report', 'release-report', 'template-report', 'submission-report', 'frontend-report')
    evidence = {name: json.loads((stage / (name + '.json')).read_text()) for name in names}
    assert all(value['passed'] for value in evidence.values())
    final = {'passed': False, 'publicProblems': int(mysql("SELECT COUNT(*) FROM question_public_number WHERE status='ONLINE'")),
        'activeTestAccounts': int(mysql("SELECT COUNT(*) FROM user WHERE LEFT(userAccount,13)='prelude_test_' AND isDelete=0")),
        'statisticsMismatches': len(mysql(Path('/srv/nemo-oj/deploy/migration/checks/submission_statistics_audit.sql').read_text()).splitlines()),
        'sandboxResidual': len(run(['docker', 'ps', '-aq', '--filter', 'label=nemo-oj.sandbox=true']).splitlines()),
        'old166Unchanged': old_fingerprint() == evidence['release-report']['old166Fingerprint'], 'services': {}}
    for service in ('judge-service', 'question-service'):
        meta = json.loads(run(['docker', 'inspect', container('core', service)]))[0]
        final['services'][service] = {'image': meta['Config']['Image'], 'health': meta['State'].get('Health', {}).get('Status')}
    queues = run(['docker', 'exec', container('core', 'rabbitmq'), 'rabbitmqctl', 'list_queues', 'name', 'messages_ready', 'messages_unacknowledged', '--formatter=json'])
    final['queues'] = json.loads(queues[queues.index('['):])
    assert final['publicProblems'] == 306 and final['activeTestAccounts'] == 0
    assert final['statisticsMismatches'] == 0 and final['old166Unchanged']
    assert all(value['health'] == 'healthy' for value in final['services'].values())
    final['passed'] = True; evidence['final-audit'] = final
    atomic_json(stage / 'evidence.json', evidence)
    export = stage / 'evidence.json'
    os.chown(export, stage.stat().st_uid, stage.stat().st_gid)
    print(json.dumps(final), flush=True)


if __name__ == '__main__':
    {'probe': probe, 'deploy': deploy, 'sweep': sweep, 'audit': audit}[sys.argv[1]](Path(sys.argv[2]))
