"""Publish static files or the two-file Agent patch. No backups or secret output."""
import hashlib,json,os,subprocess,sys,tarfile,tempfile,time
from pathlib import Path

stage=Path(sys.argv[1]).resolve();action=sys.argv[2]
def run(args,timeout=180):
    result=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=timeout)
    if result.returncode:raise RuntimeError('Release command failed; output suppressed: '+args[0])
    return result.stdout
def digest(data):return hashlib.sha256(data).hexdigest()
def atomic(path,data,mode=0o644):
    fd,name=tempfile.mkstemp(dir=path.parent,prefix='.redblack-');os.fchmod(fd,mode)
    with os.fdopen(fd,'wb') as f:f.write(data);f.flush();os.fsync(f.fileno())
    os.replace(name,path)
def report(data):
    atomic(stage/(action+'-release.json'),json.dumps(data).encode(),0o600)
    print(json.dumps(data),flush=True)

if action=='frontend':
    root=Path('/srv/nemo-oj/nemo-frontend/dist').resolve();assert root.is_dir()
    manifest=json.loads((stage/'frontend-manifest.json').read_text())
    with tarfile.open(stage/'frontend.tar.gz') as archive:
        members=archive.getmembers()
        assert all(m.isfile() and (root/m.name).resolve().is_relative_to(root) for m in members)
        assert set(m.name for m in members)==set(manifest)
        files={m.name:archive.extractfile(m).read() for m in members}
    assert all(digest(data)==manifest[name] for name,data in files.items())
    oldindex=(root/'index.html').read_bytes()
    oldgzip=(root/'index.html.gz').read_bytes() if (root/'index.html.gz').exists() else None
    before=json.loads(run(['docker','inspect','nemo-oj-proxy-1']))[0]['State']['StartedAt']
    try:
        # Retain old content-hashed assets for already-open browser tabs.
        for name,data in files.items():
            if name in ('index.html','index.html.gz'):continue
            path=root/name;path.parent.mkdir(parents=True,exist_ok=True);atomic(path,data)
        if 'index.html.gz' in files:atomic(root/'index.html.gz',files['index.html.gz'])
        atomic(root/'index.html',files['index.html'])
        after=json.loads(run(['docker','inspect','nemo-oj-proxy-1']))[0]['State']['StartedAt']
        assert before==after
        report({'passed':True,'filesPublished':len(files),'indexSha256':manifest['index.html'],'proxyRestarted':False,'oldAssetsRetained':True,'backupsCreated':False})
    except BaseException:
        atomic(root/'index.html',oldindex)
        if oldgzip is not None:atomic(root/'index.html.gz',oldgzip)
        raise
elif action=='agent':
    old=json.loads(run(['docker','inspect','nemo-ai-nemo-agent-1']))[0]
    assert old['Config']['Image'] in ('nemo-oj/agent:validation-round5-20261013','nemo-oj/agent:redblack-guidance-20261014')
    manifest=json.loads((stage/'agent-manifest.json').read_text())
    assert set(manifest['files']) in ({'analysis_graph.py','generation_topic_policy.py'}, {'analysis_graph.py','generation_topic_policy.py','redblack_topics.py'})
    previous=run(['docker','exec',old['Id'],'cat','/app/nemo_agent/analysis_graph.py']).decode().replace('\r\n','\n').rstrip()
    assert digest(previous.encode())==manifest['baseAnalysisSha256'],'Deployed source changed; review again'
    for name,sha in manifest['files'].items():
        content=(stage/name).read_bytes();assert digest(content)==sha
    image='nemo-oj/agent:redblack-authored-20261014' if 'redblack_topics.py' in manifest['files'] else 'nemo-oj/agent:redblack-guidance-20261014'
    (stage/'Dockerfile').write_text('FROM '+old['Config']['Image']+'\n'+''.join('COPY --chown=10001:10001 '+name+' /app/nemo_agent/'+name+'\n' for name in sorted(manifest['files'])))
    run(['docker','build','--network=none','--pull=false','-t',image,str(stage)])
    run(['docker','run','--rm','--network=none','--memory=256m','--entrypoint=python',image,'-c',
         'from nemo_agent.analysis_graph import create_analysis_graph;from nemo_agent.generation_topic_policy import generation_topic_policy;assert "HARD默认" in generation_topic_policy("红黑树");print("import-ready")'])
    labels=old['Config']['Labels'];env=Path(labels['com.docker.compose.project.environment_file'])
    assert str(env)=='/srv/nemo-ai/deploy/.env.agent' and env.stat().st_mode&0o777==0o600
    original=env.read_bytes();text=original.decode();assert sum(l.startswith('NEMO_AGENT_IMAGE=') for l in text.splitlines())==1
    command=['docker','compose','--project-name','nemo-ai','--env-file',str(env)]
    for file in dict.fromkeys(labels['com.docker.compose.project.config_files'].split(',')):command+=['-f',file]
    config=json.loads(run(command+['config','--format','json']))
    updated='\n'.join('NEMO_AGENT_IMAGE='+image if l.startswith('NEMO_AGENT_IMAGE=') else l for l in text.splitlines())+'\n'
    start=time.monotonic()
    step='configuration'
    try:
        atomic(env,updated.encode(),0o600)
        actual=json.loads(run(command+['config','--format','json']));actual['services']['nemo-agent']['image']=config['services']['nemo-agent']['image'];assert actual==config
        step='recreate';run(command+['up','-d','--no-deps','--pull','never','nemo-agent'])
        time.sleep(8)
        new=json.loads(run(['docker','inspect','nemo-ai-nemo-agent-1']))[0]
        step='running';assert new['State']['Status']=='running' and new['RestartCount']==0
        step='environment';assert dict(e.split('=',1) for e in new['Config']['Env'])==dict(e.split('=',1) for e in old['Config']['Env'])
        code='import hashlib,json;from pathlib import Path;from nemo_agent.config import Settings;s=Settings();assert s.event_driven_validation_enabled and s.python_preflight_enabled and s.model_concurrency==2;print(json.dumps({p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in Path("/app/nemo_agent").glob("*.py")}))'
        step='runtime-flags';hashes=json.loads(run(['docker','exec',new['Id'],'python','-c',code]))
        step='source-hashes'
        assert all(hashes.get(name)==sha for name,sha in manifest['files'].items())
        logs=run(['docker','logs','--since',new['State']['StartedAt'],new['Id']]).decode()
        # Worker logs are on stderr; do not print them or treat them as readiness proof.
        report({'passed':True,'oldImage':old['Config']['Image'],'image':image,'files':len(manifest['files']),'environmentUnchanged':True,'commandToInspectionSeconds':round(time.monotonic()-start,3),'backupsCreated':False})
    except BaseException as failure:
        atomic(env,original,0o600);run(command+['up','-d','--no-deps','--pull','never','nemo-agent'])
        print(json.dumps({'rolledBack':True,'step':step,'exceptionType':type(failure).__name__}),flush=True)
        raise RuntimeError('Agent release failed; prior image restored') from None
else:raise ValueError('Unknown action')
