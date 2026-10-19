"""Preserve verified overlays at boot without restarting running services."""
import json,os,subprocess,sys,tempfile
from pathlib import Path
sys.path.insert(0,'/usr/local/lib/nemo-ops')
from common import atomic_json
stage=Path(sys.argv[1]);base=Path('/srv/nemo-oj');deploy=base/'deploy'
files=['docker-compose.yml','compose.core-cloud.yml','compose.core-4g-preview.yml','compose.training.yml','compose.validation-prewarm.yml','compose.generation-cache.yml']
for file in files:assert (deploy/file).is_file()
args=['/usr/bin/docker','compose','--project-name','nemo-oj','--env-file','deploy/.env']
for file in files:args+=['-f','deploy/'+file]
r=subprocess.run(args+['config','--format','json'],cwd=base,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=15)
assert r.returncode==0
config=json.loads(r.stdout)
assert config['services']['sandbox']['environment']['SANDBOX_BACKGROUND_IDLE_GRACE_MS']=='5000'
assert config['services']['sandbox']['environment']['SANDBOX_TRUSTED_COMPILE_CACHE_ENABLED']=='true'
assert config['services']['question-service']['environment']['AI_PRIVATE_REPAIR_CHECKPOINT_REUSE_ENABLED']=='true'
assert config['services']['user-service']['image']=='nemo-oj/user-service:session-fix-final-20261014'
folder=Path('/etc/systemd/system/nemo-core.service.d');folder.mkdir(exist_ok=True)
path=folder/'30-verified-generation-overlays.conf'
old=path.read_text() if path.exists() else None
value='[Service]\nExecStart=\nExecStart='+' '.join(args+['up','-d','--no-build'])+'\nExecStop=\nExecStop='+' '.join(args+['stop'])+'\n'
fd,tmp=tempfile.mkstemp(dir=folder,prefix='.round6-');os.fchmod(fd,0o644)
with os.fdopen(fd,'w') as f:f.write(value);f.flush();os.fsync(f.fileno())
os.replace(tmp,path)
r=subprocess.run(['systemctl','daemon-reload'],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=15)
if r.returncode:
 if old is None:path.unlink()
 else:path.write_text(old)
 raise RuntimeError('Startup unit reload failed; drop-in restored')
report={'passed':True,'restartPerformed':False,'explicitProjectName':'nemo-oj','retainedIdleGraceMs':5000,'overlayCount':len(files)}
atomic_json(stage/'startup-persistence.json',report);print(json.dumps(report))
