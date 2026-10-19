"""Agent event-wait release; credentials stay on host, prior image retained."""
import hashlib,json,os,subprocess,sys,tempfile,time
from pathlib import Path
stage=Path(sys.argv[1])
def run(args,timeout=180):
    p=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=timeout)
    if p.returncode:raise RuntimeError('Release failed; command output withheld')
    return p.stdout
def meta():
    ids=run(['docker','ps','-aq','--filter','label=com.docker.compose.project=nemo-ai','--filter','label=com.docker.compose.service=nemo-agent']).decode().split();assert len(ids)==1
    return json.loads(run(['docker','inspect',ids[0]]))[0]
old=meta();assert old['Config']['Image']=='nemo-oj/agent:python-preflight-20261013'
manifest=json.loads((stage/'manifest.json').read_text());assert set(manifest)=={'analysis_graph.py','worker.py','config.py','signed_client.py'}
for name,digest in manifest.items():
    assert hashlib.sha256((stage/name).read_bytes()).hexdigest()==digest;compile((stage/name).read_text(),name,'exec')
image='nemo-oj/agent:validation-round5-20261013'
(stage/'Dockerfile').write_text('FROM '+old['Config']['Image']+'\n'+''.join('COPY --chown=10001:10001 '+name+' /app/nemo_agent/'+name+'\n' for name in sorted(manifest)))
run(['docker','build','--network=none','--pull=false','-t',image,str(stage)])
run(['docker','run','--rm','--network=none','--memory=256m','--entrypoint=python',image,'-c',
     'from nemo_agent.analysis_graph import create_analysis_graph;from nemo_agent.config import Settings;from nemo_agent.trusted_templates import CATALOG;from langgraph.types import Command;assert len(CATALOG)==18 and not Settings().event_driven_validation_enabled'])
labels=old['Config']['Labels'];env=Path(labels['com.docker.compose.project.working_dir'])/'.env.agent';assert str(env).startswith('/srv/nemo-ai/');original=env.read_text();assert env.stat().st_mode&0o777==0o600
command=['docker','compose','--project-name','nemo-ai','--env-file',str(env)]
for file in dict.fromkeys(labels['com.docker.compose.project.config_files'].split(',')):command+=['-f',file]
before=json.loads(run(command+['config','--format','json']))
overlay=env.parent/'compose.validation-events.yml';overlay.write_text('services:\n  nemo-agent:\n    environment:\n      AI_EVENT_DRIVEN_VALIDATION_ENABLED: "true"\n')
active=command+['-f',str(overlay)]
changed='\n'.join(l for l in original.splitlines() if not l.startswith('NEMO_AGENT_IMAGE='))+'\nNEMO_AGENT_IMAGE='+image+'\n'
def write(value):
    fd,tmp=tempfile.mkstemp(dir=env.parent,prefix='.round5-');os.fchmod(fd,0o600)
    with os.fdopen(fd,'w') as f:f.write(value);f.flush();os.fsync(f.fileno())
    os.replace(tmp,env)
try:
    write(changed);after=json.loads(run(active+['config','--format','json']));expected=json.loads(json.dumps(before))
    expected['services']['nemo-agent']['image']=image;expected['services']['nemo-agent']['environment']['AI_EVENT_DRIVEN_VALIDATION_ENABLED']='true'
    assert expected==after,'Unexpected effective config changes'
    run(active+['up','-d','--no-deps','--pull','never','nemo-agent'])
    time.sleep(10);new=meta();assert new['Config']['Image']==image and new['State']['Status']=='running' and new['RestartCount']==0
    # Printing only a boolean, never provider configuration or credentials.
    assert run(['docker','exec',new['Id'],'python','-c','from nemo_agent.config import Settings;s=Settings();assert s.event_driven_validation_enabled and s.python_preflight_enabled and s.model_concurrency==2;print("event-ready")']).strip()==b'event-ready'
    report={'passed':True,'oldImage':old['Config']['Image'],'newImage':image,'eventDrivenValidation':True,'files':len(manifest),'backupsCreated':False}
    (stage/'release-report.json').write_text(json.dumps(report));print(json.dumps(report),flush=True)
except BaseException:
    write(original);run(command+['up','-d','--no-deps','--pull','never','nemo-agent'])
    raise RuntimeError('Agent release failed; prior configuration restored') from None
