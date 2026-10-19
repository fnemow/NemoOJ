"""Prepare first, then deploy one service at a time. No secrets or data backups."""
import copy,hashlib,io,json,os,subprocess,sys,tarfile,tempfile,time,urllib.request,zipfile
from pathlib import Path
sys.path.insert(0,'/usr/local/lib/nemo-ops')
from common import atomic_json,mysql
stage=Path(sys.argv[1]);action=sys.argv[2];envfile=Path('/srv/nemo-oj/deploy/.env')
allow={
 'sandbox':('com/nemo/nemosandbox/TrustedCompileArtifactCache','com/nemo/nemosandbox/JavaDockerCodeSandBox','com/nemo/nemosandbox/config/SandboxExecutionMetrics','com/nemo/nemosandbox/model/ExecuteCodeRequestV2'),
 'judge-service':('com/nemo/OJbackendjudgeservice/judge/PrivateProblemValidationService',),
 'question-service':('com/nemo/OJbackendquestionservice/service/PrivateValidationCheckpointService',),
 'model':('com/nemo/OJbackendmodel/model/codesandbox/ExecuteCodeRequestV2',)}
def run(args,timeout=180):
 r=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=timeout)
 if r.returncode:raise RuntimeError('Release operation failed; output withheld: '+args[0])
 return r.stdout
def meta(service):return json.loads(run(['docker','inspect','nemo-oj-'+service+'-1']))[0]
def patched(data,changes):
 output=io.BytesIO()
 with zipfile.ZipFile(io.BytesIO(data)) as before,zipfile.ZipFile(output,'w') as after:
  for item in before.infolist():after.writestr(copy.copy(item),changes.get(item.filename,before.read(item)))
  for name,value in changes.items():
   if name not in before.namelist():after.writestr(name,value)
 with zipfile.ZipFile(io.BytesIO(data)) as before,zipfile.ZipFile(io.BytesIO(output.getvalue())) as after:
  assert all(before.read(name)==after.read(name) for name in before.namelist() if name not in changes)
 return output.getvalue()
def text(path,value,mode=0o600):
 fd,tmp=tempfile.mkstemp(dir=path.parent,prefix='.round6-');os.fchmod(fd,mode)
 with os.fdopen(fd,'w') as f:f.write(value);f.flush();os.fsync(f.fileno())
 os.replace(tmp,path)
def prepare():
 with tarfile.open(stage/'classes.tgz') as archive:
  for member in archive.getmembers():assert member.isfile() and (stage/'classes'/member.name).resolve().is_relative_to((stage/'classes').resolve())
  archive.extractall(stage/'classes',filter='data')
 changes={module:{} for module in allow}
 for name,sha in json.loads((stage/'manifest.json').read_text()).items():
  module,path=name.split('/',1);assert module in allow and path.startswith(allow[module]) and path.endswith('.class')
  data=(stage/'classes'/name).read_bytes();assert hashlib.sha256(data).hexdigest()==sha;changes[module][path]=data
 for service in ('sandbox','judge-service','question-service'):
  m=meta(service);jar=run(['docker','exec',m['Id'],'cat','/app/service.jar'])
  edits={'BOOT-INF/classes/'+name:value for name,value in changes[service].items()}
  if service=='judge-service':
   library='BOOT-INF/lib/OJ-backend-model-0.0.1-SNAPSHOT.jar'
   with zipfile.ZipFile(io.BytesIO(jar)) as before:edits[library]=patched(before.read(library),changes['model'])
  folder=stage/service;folder.mkdir(exist_ok=True);(folder/'service.jar').write_bytes(patched(jar,edits))
  (folder/'Dockerfile').write_text('FROM '+m['Config']['Image']+'\nCOPY service.jar /app/service.jar\n')
  image='nemo-oj/'+service+':generation-round6-20261014';run(['docker','build','--network=none','--pull=false','-t',image,str(folder)],240)
  atomic_json(stage/(service+'-prepared.json'),{'previousImage':m['Config']['Image'],'image':image,'unrelatedJarEntriesUnchanged':True})
 print(json.dumps({'prepared':True}),flush=True)
def command(service):
 args=['docker','compose','--project-name','nemo-oj','--env-file',str(envfile)]
 files=list(dict.fromkeys(meta(service)['Config']['Labels']['com.docker.compose.project.config_files'].split(',')))
 for path in files:assert Path(path).parent==envfile.parent;args+=['-f',path]
 return args
def ready(service):
 end=time.monotonic()+150
 while time.monotonic()<end:
  try:
   m=meta(service);e=dict(v.split('=',1) for v in m['Config']['Env']);ip=next(iter(m['NetworkSettings']['Networks'].values()))['IPAddress']
   with urllib.request.urlopen('http://'+ip+':'+e.get('MANAGEMENT_PORT',{'sandbox':'9190','judge-service':'9104','question-service':'9103'}[service])+'/actuator/health/readiness',timeout=3) as r:
    if json.load(r).get('status')=='UP':return
  except Exception:pass
  time.sleep(2)
 raise RuntimeError('Readiness deadline exceeded')
def deploy(service):
 assert service in ('sandbox','judge-service','question-service')
 assert mysql("SELECT COUNT(*) FROM ai_task WHERE status IN ('QUEUED','RUNNING')")=='0'
 assert mysql('SELECT COUNT(*) FROM question_submit WHERE isDelete=0 AND status IN (0,1)')=='0'
 assert not run(['docker','ps','-aq','--filter','label=nemo-oj.sandbox=true']).strip()
 report=json.loads((stage/(service+'-prepared.json')).read_text());assert meta(service)['Config']['Image']==report['previousImage']
 original=envfile.read_text();assert 'REGISTRATION_ENABLED=false' in original.splitlines()
 old=command(service);before=json.loads(run(old+['config','--format','json']))
 overlay=envfile.parent/'compose.generation-cache.yml';overlay_before=overlay.read_text() if overlay.exists() else None
 value=(stage/'compose.generation-cache.yml').read_text();text(overlay,value,0o644)
 active=old.copy()
 if str(overlay) not in active:active+=['-f',str(overlay)]
 variable={'sandbox':'NEMO_SANDBOX_IMAGE','judge-service':'NEMO_JUDGE_IMAGE','question-service':'NEMO_QUESTION_IMAGE'}[service]
 updates={variable:report['image'],'SANDBOX_TRUSTED_COMPILE_CACHE_ENABLED':'true','AI_PRIVATE_REPAIR_CHECKPOINT_REUSE_ENABLED':'true'}
 changed='\n'.join(line for line in original.splitlines() if line.split('=',1)[0] not in updates)+'\n'+'\n'.join(k+'='+v for k,v in updates.items())+'\n'
 started=time.monotonic()
 try:
  text(envfile,changed);after=json.loads(run(active+['config','--format','json']))
  expected=copy.deepcopy(before);expected['services'][service]['image']=report['image']
  expected['services']['sandbox']['environment']['SANDBOX_TRUSTED_COMPILE_CACHE_ENABLED']='true'
  expected['services']['question-service']['environment']['AI_PRIVATE_REPAIR_CHECKPOINT_REUSE_ENABLED']='true'
  assert after==expected,'Unexpected effective configuration change'
  run(active+['up','-d','--no-deps','--pull','never',service]);ready(service)
  report.update(passed=True,secondsUntilReady=round(time.monotonic()-started,3),backupsCreated=False)
  atomic_json(stage/(service+'-release.json'),report);print(json.dumps(report),flush=True)
 except BaseException:
  text(envfile,original)
  if overlay_before is not None:text(overlay,overlay_before,0o644)
  else:overlay.unlink(missing_ok=True)
  run(old+['up','-d','--no-deps','--pull','never',service]);ready(service)
  raise RuntimeError('Round6 deploy failed; previous service configuration restored') from None
if action=='prepare':prepare()
elif action=='deploy':deploy(sys.argv[3])
else:raise ValueError('Unsupported action')
