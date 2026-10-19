"""Run on the chosen cloud host. Retains original images for rollback; creates no data backups."""
import copy,hashlib,io,json,os,subprocess,sys,tarfile,tempfile,time,zipfile
from pathlib import Path

kind,action=sys.argv[1:];assert kind in ('core','agent') and action in ('prepare','activate')
stage=Path('/home/ubuntu/nemo-generation-20261011' if kind=='core' else '/root/nemo-generation-20261011')
deploy=Path('/srv/nemo-oj/deploy' if kind=='core' else '/srv/nemo-ai/deploy')
services=['judge-service','question-service'] if kind=='core' else ['nemo-agent']
prefix='nemo-oj-' if kind=='core' else 'nemo-ai-'
def run(args,timeout=240):
 p=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=timeout)
 if p.returncode:raise RuntimeError('Release command failed: '+args[0]+'; sensitive output suppressed')
 return p.stdout+p.stderr if args[:2]==['docker','logs'] else p.stdout
def inspect(service):return json.loads(run(['docker','inspect',prefix+service+'-1']))[0]
previous={s:inspect(s) for s in services};labels=previous[services[0]]['Config']['Labels']
env=Path(labels['com.docker.compose.project.environment_file']);assert env.parent==deploy
original=env.read_text();compose=['docker','compose','--project-name',labels['com.docker.compose.project'],'--env-file',str(env)]
for name in labels['com.docker.compose.project.config_files'].split(','):
 assert Path(name).parent==deploy;compose+=['-f',name]
def write(path,value):
 fd,tmp=tempfile.mkstemp(dir=str(path.parent),prefix='.generation-');os.fchmod(fd,path.stat().st_mode&0o777)
 with os.fdopen(fd,'w') as f:f.write(value);f.flush();os.fsync(f.fileno())
 os.replace(tmp,str(path))
def extract(name):
 with tarfile.open(str(stage/name)) as archive:
  for entry in archive.getmembers():assert str((stage/entry.name).resolve()).startswith(str(stage.resolve())+os.sep) and (entry.isfile() or entry.isdir())
  archive.extractall(str(stage))
def patch_zip(data,replacements):
 out=io.BytesIO()
 with zipfile.ZipFile(io.BytesIO(data)) as old:
  with zipfile.ZipFile(out,'w') as new:
   for info in old.infolist():new.writestr(copy.copy(info),replacements.get(info.filename,old.read(info)))
   for name,value in replacements.items():
    if name not in old.namelist():new.writestr(name,value)
  with zipfile.ZipFile(io.BytesIO(out.getvalue())) as new:
   assert all(old.read(n)==new.read(n) for n in old.namelist() if n not in replacements)
 return out.getvalue()
tag='generation-v3-final2-20261011' if kind=='agent' else 'generation-v3-20261011'
images={s:'nemo-oj/'+('agent' if s=='nemo-agent' else s)+':'+tag for s in services}
if action=='prepare':
 extract('generation-core.tar.gz' if kind=='core' else 'generation-agent.tar.gz')
 if kind=='core':
  manifest=json.loads((stage/'manifest.json').read_text())
  for service in services:
   replacements={}
   for entry,digest in manifest[service].items():
    data=(stage/service/entry).read_bytes();assert hashlib.sha256(data).hexdigest()==digest;replacements[entry]=data
   old=run(['docker','exec',prefix+service+'-1','cat','/app/service.jar'])
   model={}
   for entry,digest in manifest['model'].items():
    data=(stage/'model'/entry).read_bytes();assert hashlib.sha256(data).hexdigest()==digest;model[entry]=data
   with zipfile.ZipFile(io.BytesIO(old)) as jar:
    dependency=[n for n in jar.namelist() if n.startswith('BOOT-INF/lib/OJ-backend-model-') and n.endswith('.jar')];assert len(dependency)==1
    replacements[dependency[0]]=patch_zip(jar.read(dependency[0]),model)
   (stage/service/'service.jar').write_bytes(patch_zip(old,replacements))
   (stage/service/'Dockerfile').write_text('FROM '+previous[service]['Config']['Image']+'\nCOPY --chown=10001:10001 service.jar /app/service.jar\n')
   run(['docker','build','--network=none','--pull=false','-t',images[service],str(stage/service)])
 else:
  (stage/'Dockerfile').write_text('FROM '+previous['nemo-agent']['Config']['Image']+'\nCOPY --chown=10001:10001 nemo_agent /app/nemo_agent\n')
  run(['docker','build','--network=none','--pull=false','-t',images['nemo-agent'],str(stage)])
 print(json.dumps({'prepared':True,'images':images,'databaseMigration':False,'backupsCreated':False}),flush=True);sys.exit(0)
if kind=='core':
 sys.path.insert(0,'/usr/local/lib/nemo-ops');from common import mysql
 assert mysql("SELECT COUNT(*) FROM ai_task WHERE status IN ('QUEUED','RUNNING')")=='0','AI jobs must drain first'
 assert mysql("SELECT COUNT(*) FROM question_submit WHERE status IN (0,1) AND isDelete=0")=='0','Judging must drain first'
 assert 'REGISTRATION_ENABLED=false' in original.splitlines()
overlay=deploy/'compose.generation.yml'
release=compose[:]
if kind=='agent':
 overlay.write_text('services:\n  nemo-agent:\n    environment:\n      AI_CANONICAL_GENERATION_ENABLED: ${AI_CANONICAL_GENERATION_ENABLED:-true}\n      AI_TRUSTED_TEMPLATES_ENABLED: ${AI_TRUSTED_TEMPLATES_ENABLED:-true}\n')
 if str(overlay) not in release:release+=['-f',str(overlay)]
keys={'judge-service':'NEMO_JUDGE_IMAGE','question-service':'NEMO_QUESTION_IMAGE','nemo-agent':'NEMO_AGENT_IMAGE'}
updated='\n'.join(l for l in original.splitlines() if not any(l.startswith(keys[s]+'=') for s in services))+'\n'+''.join(keys[s]+'='+images[s]+'\n' for s in services)
before=json.loads(run(compose+['config','--format','json']))
try:
 write(env,updated);after=json.loads(run(release+['config','--format','json']));normalized=copy.deepcopy(after)
 for s in services:
  normalized['services'][s]['image']=before['services'][s]['image']
  if kind=='agent':
   for flag in ('AI_CANONICAL_GENERATION_ENABLED','AI_TRUSTED_TEMPLATES_ENABLED'):
    old=before['services'][s].get('environment',{}).get(flag)
    if old is None:normalized['services'][s]['environment'].pop(flag,None)
    else:normalized['services'][s]['environment'][flag]=old
 assert normalized==before,'Unrelated compose configuration changed'
 for s in services:
  run(release+['up','-d','--no-deps','--pull','never',s]);deadline=time.monotonic()+180
  while time.monotonic()<deadline:
   state=inspect(s)['State']
   if state['Status']!='running':raise RuntimeError(s+' stopped')
   if kind=='core' and state.get('Health',{}).get('Status')=='healthy':break
   if kind=='agent':
    logs=run(['docker','logs','--since','2m',prefix+s+'-1']).decode()
    if all('consuming '+n in logs for n in ['nemo.ai.tasks','nemo.ai.analysis','nemo.ai.plans','nemo.ai.private']):break
   time.sleep(3)
  else:raise RuntimeError(s+' startup timed out')
  print(json.dumps({'deployed':s,'image':images[s],'ready':True}),flush=True)
 (stage/'release.json').write_text(json.dumps({'images':images,'previousImages':{s:previous[s]['Config']['Image'] for s in services},'databaseMigration':False,'backupsCreated':False}))
except Exception:
 write(env,original);run(compose+['up','-d','--no-deps','--pull','never']+services);print('Release rolled back to original images',flush=True);raise
