"""Add calendar-only classes to the running question service; retain other jar entries."""
import copy, hashlib, io, json, os, subprocess, sys, tarfile, tempfile, time, zipfile
from pathlib import Path
sys.path.insert(0,'/usr/local/lib/nemo-ops')
from common import mysql, atomic_json, container

stage=Path(sys.argv[1]);envfile=Path('/srv/nemo-oj/deploy/.env')
def run(args):
    result=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=240)
    if result.returncode:raise RuntimeError('Release command failed; sensitive output suppressed: '+args[0])
    return result.stdout
def inspect():return json.loads(run(['docker','inspect',container('core','question-service')]))[0]

old=inspect();assert old['State']['Health']['Status']=='healthy'
with tarfile.open(stage/'backend.tar.gz') as archive:
    for member in archive.getmembers():
        assert (stage/'classes'/member.name).resolve().is_relative_to((stage/'classes').resolve()) and member.isfile()
    archive.extractall(stage/'classes',filter='data')
manifest=json.loads((stage/'backend-manifest.json').read_text());assert len(manifest)==6
changes={}
for name,digest in manifest.items():
    assert name.startswith('BOOT-INF/classes/com/nemo/OJbackendquestionservice/')
    data=(stage/'classes'/name.removeprefix('BOOT-INF/classes/')).read_bytes()
    assert hashlib.sha256(data).hexdigest()==digest;changes[name]=data
jar=run(['docker','exec',old['Id'],'cat','/app/service.jar']);output=io.BytesIO()
with zipfile.ZipFile(io.BytesIO(jar)) as before,zipfile.ZipFile(output,'w') as after:
    assert not set(changes).intersection(before.namelist()),'Calendar already installed; inspect before re-deploying'
    for entry in before.infolist():after.writestr(copy.copy(entry),before.read(entry))
    for name,data in changes.items():after.writestr(name,data)
with zipfile.ZipFile(io.BytesIO(jar)) as before,zipfile.ZipFile(io.BytesIO(output.getvalue())) as after:
    assert all(before.read(name)==after.read(name) for name in before.namelist())
image='nemo-oj/question-service:submission-calendar-20261012'
folder=stage/'image';folder.mkdir(exist_ok=True)
(folder/'service.jar').write_bytes(output.getvalue())
(folder/'Dockerfile').write_text('FROM '+old['Config']['Image']+'\nCOPY service.jar /app/service.jar\n')
run(['docker','build','--network=none','--pull=false','-t',image,str(folder)])
assert mysql('SELECT COUNT(*) FROM question_submit WHERE isDelete=0 AND status IN(0,1)')=='0'
original=envfile.read_text();assert 'REGISTRATION_ENABLED=false' in original.splitlines()
labels=old['Config']['Labels'];assert Path(labels['com.docker.compose.project.environment_file'])==envfile
compose=['docker','compose','--project-name',labels['com.docker.compose.project'],'--env-file',str(envfile)]
for path in labels['com.docker.compose.project.config_files'].split(','):
    assert Path(path).parent==envfile.parent;compose+=['-f',path]
config=json.loads(run(compose+['config','--format','json']))
updated='\n'.join(line for line in original.splitlines() if not line.startswith('NEMO_QUESTION_IMAGE='))+'\nNEMO_QUESTION_IMAGE='+image+'\n'
def write(value):
    fd,name=tempfile.mkstemp(dir=envfile.parent,prefix='.calendar-');os.fchmod(fd,envfile.stat().st_mode&0o777)
    with os.fdopen(fd,'w') as file:file.write(value);file.flush();os.fsync(file.fileno())
    os.replace(name,envfile)
try:
    write(updated);actual=json.loads(run(compose+['config','--format','json']))
    actual['services']['question-service']['image']=config['services']['question-service']['image'];assert actual==config
    run(compose+['up','-d','--no-deps','--pull','never','question-service'])
    deadline=time.monotonic()+180
    while time.monotonic()<deadline:
        meta=inspect()
        if meta['State'].get('Health',{}).get('Status')=='healthy':break
        if meta['State']['Status']!='running':raise RuntimeError('Question service stopped')
        time.sleep(3)
    else:raise RuntimeError('Question service readiness timeout')
    report={'passed':True,'image':image,'previousImage':old['Config']['Image'],
            'newClasses':len(changes),'allPreviousJarEntriesUnchanged':True,'registrationEnabled':False,'backupsCreated':False}
    atomic_json(stage/'backend-release-report.json',report);print(json.dumps(report),flush=True)
except Exception:
    write(original);run(compose+['up','-d','--no-deps','--pull','never','question-service'])
    raise
