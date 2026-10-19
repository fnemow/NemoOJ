"""Targeted core release; preserve images, create no data backups, suppress secrets."""
import copy,hashlib,io,json,os,subprocess,sys,tarfile,tempfile,time,urllib.request,zipfile
from pathlib import Path
sys.path.insert(0,'/usr/local/lib/nemo-ops')
from common import mysql,atomic_json
stage=Path(sys.argv[1]);service=sys.argv[2]
allowed={
 'sandbox':('com/nemo/nemosandbox/DockerExecutionEngine','com/nemo/nemosandbox/JavaDockerCodeSandBox','com/nemo/nemosandbox/OneShotContainerPrefetch','com/nemo/nemosandbox/config/TaskConcurrencyLimiter','com/nemo/nemosandbox/config/SandboxExecutionMetrics'),
 'judge-service':('com/nemo/OJbackendjudgeservice/judge/PrivateProblemValidationService','com/nemo/OJbackendjudgeservice/judge/ValidationCaseOrder'),
 'question-service':('com/nemo/OJbackendquestionservice/controller/AgentInternalController','com/nemo/OJbackendquestionservice/service/PrivateValidationJobs','com/nemo/OJbackendquestionservice/outbox/AiTaskLeaseReaper')}
assert service in allowed
def run(args,timeout=240):
    p=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=timeout)
    if p.returncode:raise RuntimeError('Release command failed; output withheld: '+args[0])
    return p.stdout
def meta():return json.loads(run(['docker','inspect','nemo-oj-'+service+'-1']))[0]
old=meta();manifest=json.loads((stage/'manifest.json').read_text())
with tarfile.open(stage/'classes.tgz') as archive:
    for item in archive.getmembers():assert item.isfile() and (stage/'classes'/item.name).resolve().is_relative_to((stage/'classes').resolve())
    archive.extractall(stage/'classes',filter='data')
changes={}
for name,digest in manifest.items():
    assert name.startswith(tuple('BOOT-INF/classes/'+p for p in allowed[service])) and name.endswith('.class')
    data=(stage/'classes'/name).read_bytes();assert hashlib.sha256(data).hexdigest()==digest;changes[name]=data
jar=run(['docker','exec',old['Id'],'cat','/app/service.jar']);buffer=io.BytesIO()
with zipfile.ZipFile(io.BytesIO(jar)) as before,zipfile.ZipFile(buffer,'w') as after:
    for item in before.infolist():after.writestr(copy.copy(item),changes.get(item.filename,before.read(item)))
    for name,data in changes.items():
        if name not in before.namelist():after.writestr(name,data)
with zipfile.ZipFile(io.BytesIO(jar)) as before,zipfile.ZipFile(io.BytesIO(buffer.getvalue())) as after:
    assert all(before.read(n)==after.read(n) for n in before.namelist() if n not in changes)
image='nemo-oj/'+service+(':validation-round5-final-20261013' if service=='question-service' else ':validation-round5-20261013')
(stage/'service.jar').write_bytes(buffer.getvalue());(stage/'Dockerfile').write_text('FROM '+old['Config']['Image']+'\nCOPY service.jar /app/service.jar\n')
run(['docker','build','--network=none','--pull=false','-t',image,str(stage)])
assert mysql("SELECT COUNT(*) FROM ai_task WHERE status IN ('QUEUED','RUNNING')")=='0'
assert mysql("SELECT COUNT(*) FROM question_submit WHERE status IN (0,1) AND isDelete=0")=='0'
assert not run(['docker','ps','-aq','--filter','label=nemo-oj.sandbox=true']).strip()
labels=old['Config']['Labels'];env=Path('/srv/nemo-oj/deploy/.env');original=env.read_text();assert 'REGISTRATION_ENABLED=false' in original.splitlines()
command=['docker','compose','--project-name','nemo-oj','--env-file',str(env)]
for file in dict.fromkeys(labels['com.docker.compose.project.config_files'].split(',')):command+=['-f',file]
before=json.loads(run(command+['config','--format','json']))
variable={'sandbox':'NEMO_SANDBOX_IMAGE','judge-service':'NEMO_JUDGE_IMAGE','question-service':'NEMO_QUESTION_IMAGE'}[service]
changed='\n'.join(l for l in original.splitlines() if not l.startswith(variable+'='))+'\n'+variable+'='+image+'\n'
active=command.copy()
if service=='sandbox':
    overlay=env.parent/'compose.validation-prewarm.yml'
    overlay.write_text('services:\n  sandbox:\n    environment:\n      SANDBOX_PREWARM_ENABLED: "true"\n')
    if str(overlay) not in active:active+=['-f',str(overlay)]
def write(value):
    fd,tmp=tempfile.mkstemp(dir=env.parent,prefix='.round5-');os.fchmod(fd,0o600)
    with os.fdopen(fd,'w') as out:out.write(value);out.flush();os.fsync(out.fileno())
    os.replace(tmp,env)
try:
    write(changed);after=json.loads(run(active+['config','--format','json']))
    expected=copy.deepcopy(before);expected['services'][service]['image']=image
    if service=='sandbox':expected['services'][service]['environment']['SANDBOX_PREWARM_ENABLED']='true'
    assert after==expected,'Unexpected effective config changes'
    run(active+['up','-d','--no-deps','--pull','never',service])
    deadline=time.monotonic()+180
    while time.monotonic()<deadline:
        new=meta();values=dict(v.split('=',1) for v in new['Config']['Env']);ip=next(iter(new['NetworkSettings']['Networks'].values()))['IPAddress']
        try:
            with urllib.request.urlopen('http://'+ip+':'+values.get('MANAGEMENT_PORT',{'sandbox':'9190','judge-service':'9104','question-service':'9103'}[service])+'/actuator/health/readiness',timeout=4) as response:
                if json.load(response).get('status')=='UP' and new['Config']['Image']==image:break
        except Exception:pass
        time.sleep(3)
    else:raise RuntimeError('Readiness failed')
    report={'passed':True,'service':service,'image':image,'previousImage':old['Config']['Image'],'classes':len(changes),'unrelatedJarEntriesUnchanged':True,'configChange':'image and sandbox prewarm flag' if service=='sandbox' else 'image only','backupsCreated':False}
    atomic_json(stage/'release-report.json',report);print(json.dumps(report),flush=True)
except BaseException:
    write(original);run(command+['up','-d','--no-deps','--pull','never',service])
    raise RuntimeError('Round5 release failed and old configuration restored; output withheld') from None
