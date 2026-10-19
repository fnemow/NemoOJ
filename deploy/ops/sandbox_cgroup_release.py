"""Release host cgroup fast path with one read-only mount; retain rollback configuration in memory."""
import copy
import hashlib
import io
import json
import os
import subprocess
import sys
import tarfile
import tempfile
import time
import zipfile
import urllib.request
from pathlib import Path
sys.path.insert(0,'/usr/local/lib/nemo-ops')
from common import mysql

def run(args):return subprocess.check_output(args,stderr=subprocess.DEVNULL,timeout=240)
def meta():return json.loads(run(['docker','inspect','nemo-oj-sandbox-1']))[0]

def main(stage):
    stage=Path(stage);old=meta()
    peak=run(['docker','exec',old['Id'],'cat','/sys/fs/cgroup/memory.peak']).decode().strip();assert peak.isdigit()
    manifest=json.loads((stage/'manifest.json').read_text())
    with tarfile.open(stage/'classes.tgz') as archive:
        for item in archive.getmembers():assert item.isfile() and (stage/'classes'/item.name).resolve().is_relative_to((stage/'classes').resolve())
        archive.extractall(stage/'classes',filter='data')
    changes={}
    for name,digest in manifest.items():
        assert name.startswith(tuple('BOOT-INF/classes/com/nemo/nemosandbox/'+prefix for prefix in ('DockerExecutionEngine','HostCgroupMemoryReader'))) and name.endswith('.class')
        data=(stage/'classes'/name).read_bytes();assert hashlib.sha256(data).hexdigest()==digest;changes[name]=data
    jar=run(['docker','exec',old['Id'],'cat','/app/service.jar']);buffer=io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(jar)) as before,zipfile.ZipFile(buffer,'w') as after:
        for item in before.infolist():after.writestr(copy.copy(item),changes.get(item.filename,before.read(item)))
        for name,data in changes.items():
            if name not in before.namelist():after.writestr(name,data)
    with zipfile.ZipFile(io.BytesIO(jar)) as before,zipfile.ZipFile(io.BytesIO(buffer.getvalue())) as after:
        assert all(before.read(name)==after.read(name) for name in before.namelist() if name not in changes)
    image='nemo-oj/sandbox:cgroup-fast-final-20261013'
    (stage/'service.jar').write_bytes(buffer.getvalue());(stage/'Dockerfile').write_text('FROM '+old['Config']['Image']+'\nCOPY service.jar /app/service.jar\n')
    run(['docker','build','--network=none','--pull=false','-t',image,str(stage)])
    assert mysql("SELECT COUNT(*) FROM ai_task WHERE status IN ('QUEUED','RUNNING')")=='0'
    assert mysql("SELECT COUNT(*) FROM question_submit WHERE status IN (0,1) AND isDelete=0")=='0'
    labels=old['Config']['Labels'];path=Path('/srv/nemo-oj/deploy/.env');original=path.read_text()
    command=['docker','compose','--project-name','nemo-oj','--env-file',str(path)]
    for name in dict.fromkeys(labels['com.docker.compose.project.config_files'].split(',')):command+=['-f',name]
    before=json.loads(run(command+['config','--format','json']))
    driver=run(['docker','info','--format','{{.CgroupDriver}} {{.CgroupVersion}}']).decode().strip()
    assert driver=='systemd 2', 'Host cgroup fast path requires rootful systemd cgroup v2'
    override=Path('/srv/nemo-oj/deploy/compose.core-4g-preview.yml')
    assert str(override) in command
    compose_original=override.read_text()
    import re
    match=re.search(r'^  sandbox:\n(?:(?:    .*|)\n)*',compose_original,re.M)
    assert match
    had_mount=before['services']['sandbox']['environment'].get('SANDBOX_DOCKER_HOST_CGROUP_ROOT')=='/host-cgroup'
    if had_mount:
        compose_changed=compose_original
    else:
        assert '\n    volumes:' not in match.group() and 'SANDBOX_DOCKER_HOST_CGROUP_ROOT' not in match.group()
        block=match.group().rstrip()+'\n      SANDBOX_DOCKER_HOST_CGROUP_ROOT: /host-cgroup\n    volumes:\n      - /sys/fs/cgroup:/host-cgroup:ro\n\n'
        compose_changed=compose_original[:match.start()]+block+compose_original[match.end():]
    # The hierarchy root does not expose controller counters on every kernel.
    # system.slice is a non-root cgroup; actual per-container reads are gated by acceptance.
    assert Path('/sys/fs/cgroup/system.slice/memory.events').is_file() and Path('/sys/fs/cgroup/system.slice/memory.peak').is_file()
    changed='\n'.join(line for line in original.splitlines() if not line.startswith('NEMO_SANDBOX_IMAGE='))+'\nNEMO_SANDBOX_IMAGE='+image+'\n'
    def write(value):
        fd,temp=tempfile.mkstemp(dir=path.parent,prefix='.transport-');os.fchmod(fd,0o600)
        with os.fdopen(fd,'w') as f:f.write(value);f.flush();os.fsync(f.fileno())
        os.replace(temp,path)
    try:
        step='configuration'
        write(changed);override.write_text(compose_changed);after=json.loads(run(command+['config','--format','json']))
        after['services']['sandbox']['image']=before['services']['sandbox']['image']
        assert after['services']['sandbox']['environment'].get('SANDBOX_DOCKER_HOST_CGROUP_ROOT')=='/host-cgroup'
        mounts=after['services']['sandbox']['volumes']
        host_mount=[m for m in mounts if m.get('target')=='/host-cgroup']
        assert len(host_mount)==1 and host_mount[0].get('source')=='/sys/fs/cgroup' and host_mount[0].get('read_only') is True
        if not had_mount:
            after['services']['sandbox']['environment'].pop('SANDBOX_DOCKER_HOST_CGROUP_ROOT')
            after['services']['sandbox']['volumes']=[m for m in mounts if m.get('target')!='/host-cgroup']
        assert before==after
        run(command+['up','-d','--no-deps','--pull','never','sandbox'])
        step='readiness'
        deadline=time.monotonic()+180
        while time.monotonic()<deadline:
            current=meta()
            values=dict(v.split('=',1) for v in current['Config']['Env'])
            ip=next(iter(current['NetworkSettings']['Networks'].values()))['IPAddress']
            try:
                with urllib.request.urlopen('http://'+ip+':'+values.get('MANAGEMENT_PORT','9190')+'/actuator/health/readiness',timeout=4) as response:
                    if json.load(response).get('status')=='UP' and current['State']['Status']=='running':break
            except Exception:pass
            time.sleep(3)
        else:raise RuntimeError('Sandbox readiness failed')
        report={'passed':True,'image':image,'previousImage':old['Config']['Image'],'classes':len(changes),'unrelatedJarEntriesUnchanged':True,'memoryPeakSupported':True,'onlySandboxImageAndReadOnlyCgroupMountChanged':True,'hostCgroupDriver':driver}
        (stage/'release-report.json').write_text(json.dumps(report));print(json.dumps(report),flush=True)
    except Exception:
        import re
        current=meta();logs=run(['docker','logs','--tail','120',current['Id']]).decode(errors='replace')
        diagnostics={'failedStep':step,'state':current['State']['Status'],'health':current['State'].get('Health',{}).get('Status'),'exceptionTypes':sorted(set(re.findall(r'[A-Za-z0-9_.]+(?:Exception|Error)',logs)))[:12],'memoryPeakMentioned':'memory.peak' in logs,'frames':[line.strip() for line in logs.splitlines() if line.strip().startswith('at com.nemo.')][-12:]}
        print(json.dumps(diagnostics),flush=True)
        write(original);override.write_text(compose_original);run(command+['up','-d','--no-deps','--pull','never','sandbox']);raise RuntimeError('Sandbox release rolled back') from None

if __name__=='__main__':main(sys.argv[1])
