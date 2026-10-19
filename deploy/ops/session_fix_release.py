"""Targeted session release. Retain rollback images; no data backups or secret output."""
import copy
import hashlib
import io
import json
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.request
import zipfile
from pathlib import Path
sys.path.insert(0, '/usr/local/lib/nemo-ops')
from common import atomic_json, mysql

stage = Path(sys.argv[1]); action = sys.argv[2]
ENV = Path('/srv/nemo-oj/deploy/.env')
ALLOW = {
    'common': ('com/nemo/OJbackendcommon/security/BrowserSessionSupport', 'com/nemo/OJbackendcommon/security/CsrfTokenSupport',
               'com/nemo/OJbackendcommon/ratelimit/RateLimitGuard'),
    'service-client': ('com/nemo/OJserviceclient/UserFeignClient',),
    'user-service': ('com/nemo/OJbackenduserservice/service/impl/UserServiceImpl', 'com/nemo/OJbackenduserservice/controller/UserController',
                     'com/nemo/OJbackenduserservice/controller/PublicReadinessController')}

def run(args, timeout=180):
    result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
    if result.returncode: raise RuntimeError('Release command failed; output withheld: ' + args[0])
    return result.stdout

def meta(service): return json.loads(run(['docker', 'inspect', 'nemo-oj-' + service + '-1']))[0]

def compose(service):
    command = ['docker', 'compose', '--project-name', 'nemo-oj', '--env-file', str(ENV)]
    for path in meta(service)['Config']['Labels']['com.docker.compose.project.config_files'].split(','):
        assert Path(path).parent == ENV.parent
        command += ['-f', path]
    return command

def atomic_text(path, text, mode=0o600):
    fd, name = tempfile.mkstemp(dir=path.parent, prefix='.session-fix-'); os.fchmod(fd, mode)
    with os.fdopen(fd, 'w') as file: file.write(text); file.flush(); os.fsync(file.fileno())
    os.replace(name, path)

def patch_zip(data, changes):
    output = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(data)) as before, zipfile.ZipFile(output, 'w') as after:
        for entry in before.infolist(): after.writestr(copy.copy(entry), changes.get(entry.filename, before.read(entry)))
        for name, value in changes.items():
            if name not in before.namelist(): after.writestr(name, value)
    with zipfile.ZipFile(io.BytesIO(data)) as before, zipfile.ZipFile(io.BytesIO(output.getvalue())) as after:
        assert all(before.read(name) == after.read(name) for name in before.namelist() if name not in changes)
    return output.getvalue()

def prepare():
    manifest = json.loads((stage / 'manifest.json').read_text())
    with tarfile.open(stage / 'backend.tgz') as archive:
        for member in archive.getmembers():
            assert member.isfile() and (stage / 'classes' / member.name).resolve().is_relative_to((stage / 'classes').resolve())
        archive.extractall(stage / 'classes', filter='data')
    entries = {module: {} for module in ALLOW}
    for name, digest in manifest.items():
        module, path = name.split('/', 1)
        assert module in ALLOW and path.endswith('.class') and path.startswith(ALLOW[module])
        value = (stage / 'classes' / name).read_bytes(); assert hashlib.sha256(value).hexdigest() == digest
        entries[module][path] = value
    for service in ('user-service', 'question-service'):
        old = meta(service); assert old['State']['Running']
        original = run(['docker', 'exec', old['Id'], 'cat', '/app/service.jar'])
        changes = {}
        with zipfile.ZipFile(io.BytesIO(original)) as jar:
            for module in ('common', 'service-client'):
                library = 'BOOT-INF/lib/OJ-backend-' + module + '-0.0.1-SNAPSHOT.jar'
                changes[library] = patch_zip(jar.read(library), entries[module])
        if service == 'user-service':
            changes.update({'BOOT-INF/classes/' + path: value for path, value in entries['user-service'].items()})
        folder = stage / service; folder.mkdir(exist_ok=True)
        (folder / 'service.jar').write_bytes(patch_zip(original, changes))
        (folder / 'Dockerfile').write_text('FROM ' + old['Config']['Image'] + '\nCOPY service.jar /app/service.jar\n')
        image = 'nemo-oj/' + service + ':session-fix-final-20261014'
        run(['docker', 'build', '--network=none', '--pull=false', '-t', image, str(folder)], timeout=240)
        atomic_json(stage / (service + '-prepared.json'), {'image': image, 'previousImage': old['Config']['Image'],
                    'unrelatedJarEntriesUnchanged': True, 'changedEntries': len(changes)})
    print(json.dumps({'prepared': True}), flush=True)

def wait_ready(service):
    deadline = time.monotonic() + 150
    while time.monotonic() < deadline:
        current = meta(service)
        try:
            env = dict(value.split('=', 1) for value in current['Config']['Env'])
            ip = next(iter(current['NetworkSettings']['Networks'].values()))['IPAddress']
            port = env.get('MANAGEMENT_PORT', '9102' if service == 'user-service' else '9103')
            with urllib.request.urlopen('http://' + ip + ':' + port + '/actuator/health/readiness', timeout=3) as response:
                if json.load(response).get('status') == 'UP': return
        except Exception: pass
        time.sleep(2)
    raise RuntimeError('Service readiness failed')

def deploy(service):
    assert service in ('user-service', 'question-service')
    ready = json.loads((stage / (service + '-prepared.json')).read_text()); old = meta(service)
    assert old['Config']['Image'] == ready['previousImage']
    assert mysql('SELECT COUNT(*) FROM question_submit WHERE isDelete=0 AND status IN (0,1)') == '0', 'Wait for submissions to drain'
    if service == 'question-service':
        assert mysql("SELECT COUNT(*) FROM ai_task WHERE status IN ('QUEUED','RUNNING')") == '0', 'Wait for AI tasks to drain'
    original = ENV.read_text(); assert 'REGISTRATION_ENABLED=false' in original.splitlines()
    command = compose(service); before = json.loads(run(command + ['config', '--format', 'json']))
    variable = 'NEMO_USER_IMAGE' if service == 'user-service' else 'NEMO_QUESTION_IMAGE'
    updated = '\n'.join(line for line in original.splitlines() if not line.startswith(variable + '=')) + '\n' + variable + '=' + ready['image'] + '\n'
    started = time.monotonic()
    try:
        atomic_text(ENV, updated)
        actual = json.loads(run(command + ['config', '--format', 'json']))
        expected = copy.deepcopy(before); expected['services'][service]['image'] = ready['image']; assert actual == expected
        run(command + ['up', '-d', '--no-deps', '--pull', 'never', service]); wait_ready(service)
        assert meta(service)['Config']['Image'] == ready['image']
        report = ready | {'passed': True, 'service': service, 'secondsUntilReady': round(time.monotonic()-started, 3),
                          'configChange': 'image only', 'backupsCreated': False}
        atomic_json(stage / (service + '-release.json'), report); print(json.dumps(report), flush=True)
    except BaseException:
        atomic_text(ENV, original); run(command + ['up', '-d', '--no-deps', '--pull', 'never', service]); wait_ready(service)
        raise RuntimeError('Session release failed; previous service restored') from None

def gateway():
    command = compose('gateway'); source = ENV.parent / 'docker-compose.yml'
    before = json.loads(run(command + ['config', '--format', 'json']))
    old_probe = 'http://localhost:8101/api/user/csrf'; new_probe = 'http://localhost:8101/api/user/health/readiness'
    old_source = source.read_text(); assert old_source.count(old_probe) == 1
    with urllib.request.urlopen('http://127.0.0.1:18081/api/user/health/readiness', timeout=5) as response:
        assert json.load(response)['status'] == 'UP'
    started = time.monotonic()
    try:
        atomic_text(source, old_source.replace(old_probe, new_probe), source.stat().st_mode & 0o777)
        actual = json.loads(run(command + ['config', '--format', 'json']))
        expected = copy.deepcopy(before); expected['services']['gateway']['healthcheck']['test'][-1] = new_probe
        assert actual == expected
        run(command + ['up', '-d', '--no-deps', '--pull', 'never', 'gateway'])
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            if meta('gateway')['State'].get('Health', {}).get('Status') == 'healthy': break
            time.sleep(2)
        else: raise RuntimeError('Gateway health failed')
        report = {'passed': True, 'configChange': 'gateway health probe only', 'secondsUntilHealthy': round(time.monotonic()-started, 3)}
        atomic_json(stage / 'gateway-release.json', report); print(json.dumps(report), flush=True)
    except BaseException:
        atomic_text(source, old_source, source.stat().st_mode & 0o777)
        run(command + ['up', '-d', '--no-deps', '--pull', 'never', 'gateway'])
        raise RuntimeError('Gateway probe release failed; old configuration restored') from None

def frontend():
    target = Path('/srv/nemo-oj/nemo-frontend/dist'); extracted = stage / 'frontend'; extracted.mkdir(exist_ok=True)
    with tarfile.open(stage / 'frontend.tgz') as archive:
        for member in archive.getmembers():
            assert member.isfile() and (extracted / member.name).resolve().is_relative_to(extracted.resolve())
        archive.extractall(extracted, filter='data')
    html = (extracted / 'index.html').read_text(); assert 'assets/' in html
    # Publish assets first and retain previous hashed chunks for already-open tabs.
    for file in extracted.rglob('*'):
        if file.is_file() and file.name != 'index.html':
            dest = target / file.relative_to(extracted); dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(file, dest); os.chmod(dest, 0o644)
    atomic_text(target / 'index.html', html, 0o644)
    report = {'passed': True, 'oldAssetsRetained': True, 'nginxRestarted': False, 'registrationEnabled': False}
    atomic_json(stage / 'frontend-release.json', report); print(json.dumps(report), flush=True)

if action == 'prepare': prepare()
elif action == 'deploy': deploy(sys.argv[3])
elif action == 'gateway': gateway()
elif action == 'frontend': frontend()
else: raise ValueError('Unknown release action')
