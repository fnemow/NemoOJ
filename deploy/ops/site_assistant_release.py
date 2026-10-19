"""Prepare images without outages, then update only the selected service."""
import copy,hashlib,io,json,os,subprocess,sys,tarfile,tempfile,time,urllib.request,zipfile
from pathlib import Path
stage=Path(sys.argv[1]);action=sys.argv[2];kind=sys.argv[3]
def run(args,timeout=180):
    r=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=timeout)
    if r.returncode:raise RuntimeError('Release command failed; output withheld: '+args[0])
    return r.stdout
def atomic(path,data,mode=0o644):
    fd,tmp=tempfile.mkstemp(dir=str(path.parent),prefix='.assistant-');os.fchmod(fd,mode)
    with os.fdopen(fd,'wb') as f:f.write(data);f.flush();os.fsync(f.fileno())
    os.replace(tmp,str(path))
def report(name,value):atomic(stage/(name+'.json'),json.dumps(value).encode());print(json.dumps(value),flush=True)
def unpack(filename,manifest):
    with tarfile.open(str(stage/filename)) as archive:
        entries=archive.getmembers();assert all(m.isfile() for m in entries)
        assert len(entries)==len(manifest) and {m.name for m in entries}==set(manifest)
        data={m.name:archive.extractfile(m).read() for m in entries}
    assert all(hashlib.sha256(value).hexdigest()==manifest[name] for name,value in data.items())
    return data
def meta():return json.loads(run(['docker','inspect',container]))[0]
def ready():
    end=time.monotonic()+150
    while time.monotonic()<end:
        m=meta()
        if kind=='agent':
            if m['State']['Running'] and m['RestartCount']==0:
                run(['docker','exec',container,'python','-c','from nemo_agent.analysis_graph import create_analysis_graph;from nemo_agent.schemas import SiteHelpDecision;assert SiteHelpDecision(kind="HELP",sections=["submit_flow"]).kind=="HELP"']);return
        else:
            try:
                ip=next(iter(m['NetworkSettings']['Networks'].values()))['IPAddress']
                with urllib.request.urlopen('http://'+ip+':9103/actuator/health/readiness',timeout=3) as response:
                    if json.load(response).get('status')=='UP':return
            except Exception:pass
        time.sleep(2)
    raise RuntimeError('Service readiness deadline exceeded')
if kind=='frontend':
    assert action=='deploy';root=Path('/srv/nemo-oj/nemo-frontend/dist')
    files=unpack('frontend.tar.gz',json.loads((stage/'frontend-manifest.json').read_text()))
    assert all(not Path(name).is_absolute() and '..' not in Path(name).parts for name in files)
    before=json.loads(run(['docker','inspect','nemo-oj-proxy-1']))[0]['State']['StartedAt']
    for name,data in files.items():
        if name in ('index.html','index.html.gz'):continue
        path=root/name;path.parent.mkdir(parents=True,exist_ok=True);atomic(path,data)
    if 'index.html.gz' in files:atomic(root/'index.html.gz',files['index.html.gz'])
    atomic(root/'index.html',files['index.html'])
    after=json.loads(run(['docker','inspect','nemo-oj-proxy-1']))[0]['State']['StartedAt'];assert before==after
    report('frontend-release',{'passed':True,'files':len(files),'proxyRestarted':False,'oldAssetsRetained':True,'backupsCreated':False});sys.exit(0)
assert kind in ('question','agent')
container='nemo-oj-question-service-1' if kind=='question' else 'nemo-ai-nemo-agent-1'
image='nemo-oj/'+('question-service' if kind=='question' else 'agent')+':site-assistant-20261014'
base='nemo-oj/question-service:generation-round6-20261014' if kind=='question' else 'nemo-oj/agent:redblack-authored-20261014'
if action=='prepare':
    assert meta()['Config']['Image']==base
    if kind=='agent':
        manifest=json.loads((stage/'agent-manifest.json').read_text());assert set(manifest['files'])=={'analysis_graph.py','schemas.py','worker.py'}
        assert hashlib.sha256(run(['docker','exec',container,'cat','/app/nemo_agent/analysis_graph.py'])).hexdigest()==manifest['baseAnalysisSha256']
        files=unpack('agent.tgz',manifest['files'])
        folder=stage/'agent-image';folder.mkdir(exist_ok=True)
        for name,data in files.items():(folder/name).write_bytes(data)
        (folder/'Dockerfile').write_text('FROM '+base+'\n'+''.join('COPY --chown=10001:10001 '+name+' /app/nemo_agent/'+name+'\n' for name in sorted(files)))
    else:
        manifest=json.loads((stage/'classes-manifest.json').read_text());files=unpack('classes.tgz',manifest)
        import re
        pattern=r'com/nemo/OJbackendquestionservice/(controller/(AgentInternalController|AiController|SiteAssistantController)|service/SiteHelpPolicy)(\$[A-Za-z0-9]+)?\.class'
        assert all(re.fullmatch(pattern,name) for name in files)
        edits={'BOOT-INF/classes/'+name:data for name,data in files.items()}
        jar=run(['docker','exec',container,'cat','/app/service.jar']);output=io.BytesIO()
        with zipfile.ZipFile(io.BytesIO(jar)) as old,zipfile.ZipFile(output,'w') as new:
            for entry in old.infolist():new.writestr(copy.copy(entry),edits.get(entry.filename,old.read(entry)))
            for name,data in edits.items():
                if name not in old.namelist():new.writestr(name,data)
        with zipfile.ZipFile(io.BytesIO(jar)) as old,zipfile.ZipFile(io.BytesIO(output.getvalue())) as new:
            assert all(old.read(name)==new.read(name) for name in old.namelist() if name not in edits)
        folder=stage/'question-image';folder.mkdir(exist_ok=True);(folder/'service.jar').write_bytes(output.getvalue())
        (folder/'Dockerfile').write_text('FROM '+base+'\nCOPY service.jar /app/service.jar\n')
    run(['docker','build','--network=none','--pull=false','-t',image,str(folder)],240)
    if kind=='agent':run(['docker','run','--rm','--network=none','--memory=256m','--entrypoint=python',image,'-c','from nemo_agent.analysis_graph import create_analysis_graph;from nemo_agent.schemas import SiteHelpDecision;assert SiteHelpDecision(kind="HELP",sections=["submit_flow"]).kind=="HELP"'])
    report(kind+'-prepared',{'prepared':True,'image':image,'baseImage':base,'patchedFiles':len(files),'unrelatedFilesUnchanged':True});sys.exit(0)
assert action=='deploy' and meta()['Config']['Image']==base
old=meta();labels=old['Config']['Labels'];env=Path('/srv/nemo-oj/deploy/.env' if kind=='question' else '/srv/nemo-ai/deploy/.env.agent')
assert env.stat().st_mode&0o777==0o600
if kind=='question':
    sys.path.insert(0,'/usr/local/lib/nemo-ops');from common import mysql
    assert mysql("SELECT COUNT(*) FROM ai_task WHERE status IN ('QUEUED','RUNNING')")=='0'
    assert mysql('SELECT COUNT(*) FROM question_submit WHERE isDelete=0 AND status IN (0,1)')=='0'
args=['docker','compose','--project-name','nemo-oj' if kind=='question' else 'nemo-ai','--env-file',str(env)]
for file in dict.fromkeys(labels['com.docker.compose.project.config_files'].split(',')):
    assert Path(file).parent==env.parent;args+=['-f',file]
config=json.loads(run(args+['config','--format','json']));original=env.read_bytes()
variable='NEMO_QUESTION_IMAGE' if kind=='question' else 'NEMO_AGENT_IMAGE';service='question-service' if kind=='question' else 'nemo-agent'
lines=original.decode().splitlines();assert sum(line.startswith(variable+'=') for line in lines)==1
updated='\n'.join(variable+'='+image if line.startswith(variable+'=') else line for line in lines)+'\n'
started=time.monotonic();step='configuration'
try:
    atomic(env,updated.encode(),0o600)
    actual=json.loads(run(args+['config','--format','json']));expected=copy.deepcopy(config);expected['services'][service]['image']=image;assert actual==expected
    step='recreate';run(args+['up','-d','--no-deps','--pull','never',service]);time.sleep(8 if kind=='agent' else 1)
    step='readiness';ready();new=meta()
    assert dict(e.split('=',1) for e in old['Config']['Env'])==dict(e.split('=',1) for e in new['Config']['Env'])
    if kind=='agent':
        manifest=json.loads((stage/'agent-manifest.json').read_text())
        for name,sha in manifest['files'].items():assert hashlib.sha256(run(['docker','exec',container,'cat','/app/nemo_agent/'+name])).hexdigest()==sha
    report(kind+'-release',{'passed':True,'image':image,'environmentUnchanged':True,'secondsUntilReady':round(time.monotonic()-started,3),'backupsCreated':False})
except BaseException:
    atomic(env,original,0o600);run(args+['up','-d','--no-deps','--pull','never',service]);ready()
    raise RuntimeError('Assistant release failed at '+step+'; previous image restored') from None
