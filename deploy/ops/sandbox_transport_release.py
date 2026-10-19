"""Patch two sandbox classes and their nested classes; leave other jar entries intact."""
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
        assert name.startswith(tuple('BOOT-INF/classes/com/nemo/nemosandbox/'+prefix for prefix in ('DockerClientFactory','DockerExecutionEngine','JavaDockerCodeSandBox'))) and name.endswith('.class')
        data=(stage/'classes'/name).read_bytes();assert hashlib.sha256(data).hexdigest()==digest;changes[name]=data
    jar=run(['docker','exec',old['Id'],'cat','/app/service.jar']);buffer=io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(jar)) as before,zipfile.ZipFile(buffer,'w') as after:
        for item in before.infolist():after.writestr(copy.copy(item),changes.get(item.filename,before.read(item)))
        for name,data in changes.items():
            if name not in before.namelist():after.writestr(name,data)
    with zipfile.ZipFile(io.BytesIO(jar)) as before,zipfile.ZipFile(io.BytesIO(buffer.getvalue())) as after:
        assert all(before.read(name)==after.read(name) for name in before.namelist() if name not in changes)
    image='nemo-oj/sandbox:transport-peak-final-20261013'
    (stage/'service.jar').write_bytes(buffer.getvalue());(stage/'Dockerfile').write_text('FROM '+old['Config']['Image']+'\nCOPY service.jar /app/service.jar\n')
    run(['docker','build','--network=none','--pull=false','-t',image,str(stage)])
    assert mysql("SELECT COUNT(*) FROM ai_task WHERE status IN ('QUEUED','RUNNING')")=='0'
    assert mysql("SELECT COUNT(*) FROM question_submit WHERE status IN (0,1) AND isDelete=0")=='0'
    labels=old['Config']['Labels'];path=Path('/srv/nemo-oj/deploy/.env');original=path.read_text()
    command=['docker','compose','--project-name','nemo-oj','--env-file',str(path)]
    for name in dict.fromkeys(labels['com.docker.compose.project.config_files'].split(',')):command+=['-f',name]
    before=json.loads(run(command+['config','--format','json']))
    changed='\n'.join(line for line in original.splitlines() if not line.startswith('NEMO_SANDBOX_IMAGE='))+'\nNEMO_SANDBOX_IMAGE='+image+'\n'
    def write(value):
        fd,temp=tempfile.mkstemp(dir=path.parent,prefix='.transport-');os.fchmod(fd,0o600)
        with os.fdopen(fd,'w') as f:f.write(value);f.flush();os.fsync(f.fileno())
        os.replace(temp,path)
    try:
        step='configuration'
        write(changed);after=json.loads(run(command+['config','--format','json']))
        after['services']['sandbox']['image']=before['services']['sandbox']['image'];assert before==after
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
        report={'passed':True,'image':image,'previousImage':old['Config']['Image'],'classes':len(changes),'unrelatedJarEntriesUnchanged':True,'memoryPeakSupported':True,'onlySandboxImageChanged':True}
        (stage/'release-report.json').write_text(json.dumps(report));print(json.dumps(report),flush=True)
    except Exception:
        import re
        current=meta();logs=run(['docker','logs','--tail','120',current['Id']]).decode(errors='replace')
        diagnostics={'failedStep':step,'state':current['State']['Status'],'health':current['State'].get('Health',{}).get('Status'),'exceptionTypes':sorted(set(re.findall(r'[A-Za-z0-9_.]+(?:Exception|Error)',logs)))[:12],'memoryPeakMentioned':'memory.peak' in logs,'frames':[line.strip() for line in logs.splitlines() if line.strip().startswith('at com.nemo.')][-12:]}
        print(json.dumps(diagnostics),flush=True)
        write(original);run(command+['up','-d','--no-deps','--pull','never','sandbox']);raise RuntimeError('Sandbox release rolled back') from None

if __name__=='__main__':main(sys.argv[1])
