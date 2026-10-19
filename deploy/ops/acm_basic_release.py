"""Overlay only starter classes/resources onto the current question-service image."""
import copy,hashlib,io,json,os,subprocess,sys,tarfile,tempfile,time,urllib.request,zipfile
from pathlib import Path
stage=Path(sys.argv[1]);action=sys.argv[2]
container='nemo-oj-question-service-1'
base='nemo-oj/question-service:acm-io-20261015'
image='nemo-oj/question-service:acm-basic-20261015'

def run(args,timeout=180):
    p=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=timeout)
    if p.returncode:raise RuntimeError('ACM release command failed; output withheld: '+args[0])
    return p.stdout
def meta():return json.loads(run(['docker','inspect',container]))[0]
def atomic(path,data):
    fd,name=tempfile.mkstemp(dir=str(path.parent));os.fchmod(fd,0o600)
    with os.fdopen(fd,'wb') as f:f.write(data);f.flush();os.fsync(f.fileno())
    os.replace(name,str(path))
def report(name,value):
    atomic(stage/name,json.dumps(value,indent=2).encode());print(json.dumps(value),flush=True)
def ready():
    end=time.monotonic()+150
    while time.monotonic()<end:
        try:
            m=meta();ip=next(iter(m['NetworkSettings']['Networks'].values()))['IPAddress']
            with urllib.request.urlopen('http://'+ip+':9103/actuator/health/readiness',timeout=3) as r:
                if json.load(r).get('status')=='UP':return
        except Exception:pass
        time.sleep(2)
    raise RuntimeError('Question-service readiness deadline exceeded')
assert meta()['Config']['Image']==base
manifest=json.loads((stage/'classes-manifest.json').read_text())
expected={'com/nemo/OJbackendquestionservice/service/'+name+'.class' for name in ('LanguageCapabilityService','AcmStarterTemplates')}|{'templates/acm/'+name for name in ('Main.java.txt','main.cpp.txt','main.py.txt')}
assert set(manifest)==expected
if action=='prepare':
    with tarfile.open(str(stage/'classes.tgz')) as archive:
        assert len(archive.getmembers())==len(manifest) and all(m.isfile() for m in archive.getmembers())
        files={m.name:archive.extractfile(m).read() for m in archive.getmembers()}
    assert set(files)==set(manifest) and all(hashlib.sha256(value).hexdigest()==manifest[name] for name,value in files.items())
    edits={'BOOT-INF/classes/'+name:value for name,value in files.items()}
    original=run(['docker','exec',container,'cat','/app/service.jar']);output=io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(original)) as old,zipfile.ZipFile(output,'w') as new:
        for entry in old.infolist():new.writestr(copy.copy(entry),edits.get(entry.filename,old.read(entry)))
        for name,value in edits.items():
            if name not in old.namelist():new.writestr(name,value)
    with zipfile.ZipFile(io.BytesIO(original)) as old,zipfile.ZipFile(io.BytesIO(output.getvalue())) as new:
        assert all(old.read(name)==new.read(name) for name in old.namelist() if name not in edits)
    folder=stage/'question-image';folder.mkdir(exist_ok=True)
    (folder/'service.jar').write_bytes(output.getvalue())
    (folder/'Dockerfile').write_text('FROM '+base+'\nCOPY service.jar /app/service.jar\n')
    run(['docker','build','--network=none','--pull=false','-t',image,str(folder)])
    report('prepared.json',{'prepared':True,'image':image,'patchedFiles':len(files),'unrelatedFilesUnchanged':True});sys.exit(0)
assert action=='deploy'
assert json.loads((stage/'runtime-verification.json').read_text())['passed']
old=meta();env=Path('/srv/nemo-oj/deploy/.env');assert env.stat().st_mode&0o777==0o600
sys.path.insert(0,'/usr/local/lib/nemo-ops');from common import mysql
assert mysql("SELECT COUNT(*) FROM ai_task WHERE status IN ('QUEUED','RUNNING')")=='0','Wait for active AI tasks before this release'
assert mysql('SELECT COUNT(*) FROM question_submit WHERE isDelete=0 AND status IN (0,1)')=='0','Wait for active submissions before this release'
unrelated=['nemo-oj-'+name+'-1' for name in ('proxy','gateway','user-service','judge-service','sandbox')]
before_services={m['Name']:(m['Id'],m['State']['StartedAt']) for m in json.loads(run(['docker','inspect']+unrelated))}
args=['docker','compose','--project-name','nemo-oj','--env-file',str(env)]
for filename in dict.fromkeys(old['Config']['Labels']['com.docker.compose.project.config_files'].split(',')):
    assert Path(filename).parent==env.parent;args+=['-f',filename]
before=json.loads(run(args+['config','--format','json']));original=env.read_bytes()
variable='NEMO_QUESTION_IMAGE';lines=original.decode().splitlines();assert sum(line.startswith(variable+'=') for line in lines)==1
updated='\n'.join(variable+'='+image if line.startswith(variable+'=') else line for line in lines)+'\n'
started=time.monotonic()
try:
    atomic(env,updated.encode());after=json.loads(run(args+['config','--format','json']))
    expected=copy.deepcopy(before);expected['services']['question-service']['image']=image;assert expected==after
    run(args+['up','-d','--no-deps','--pull','never','question-service']);ready()
    assert dict(e.split('=',1) for e in old['Config']['Env'])==dict(e.split('=',1) for e in meta()['Config']['Env'])
    after_services={m['Name']:(m['Id'],m['State']['StartedAt']) for m in json.loads(run(['docker','inspect']+unrelated))}
    assert before_services==after_services
    current=run(['docker','exec',container,'cat','/app/service.jar'])
    with zipfile.ZipFile(io.BytesIO(current)) as jar:
        assert all(hashlib.sha256(jar.read('BOOT-INF/classes/'+name)).hexdigest()==sha for name,sha in manifest.items())
    report('question-release.json',{'passed':True,'image':image,'questionServiceUpdated':True,'secondsUntilReady':round(time.monotonic()-started,3),'environmentUnchanged':True,'otherServicesRestarted':False,'backupsCreated':False})
except BaseException:
    atomic(env,original);run(args+['up','-d','--no-deps','--pull','never','question-service']);ready()
    raise RuntimeError('ACM template release failed; previous question-service image restored') from None
