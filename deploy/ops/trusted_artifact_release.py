"""Minimal image patch, drained deployment and persisted proof cache; no backups."""
import copy,hashlib,io,json,os,subprocess,sys,tarfile,tempfile,time,urllib.request,zipfile
from pathlib import Path
stage=Path(sys.argv[1]);kind=sys.argv[2];action=sys.argv[3]
def run(args,timeout=180):
    r=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=timeout)
    if r.returncode:raise RuntimeError('Release command failed; output withheld')
    return r.stdout
def atomic(path,data,mode=0o600):
    fd,tmp=tempfile.mkstemp(dir=str(path.parent));os.fchmod(fd,mode)
    with os.fdopen(fd,'wb') as f:f.write(data);f.flush();os.fsync(f.fileno())
    os.replace(tmp,str(path))
container='nemo-ai-nemo-agent-1' if kind=='agent' else 'nemo-oj-judge-service-1'
base='nemo-oj/agent:site-assistant-20261014' if kind=='agent' else 'nemo-oj/judge-service:generation-round6-20261014'
image='nemo-oj/'+('agent' if kind=='agent' else 'judge-service')+':trusted-artifacts-20261014'
def meta():return json.loads(run(['docker','inspect',container]))[0]
def ready():
    end=time.monotonic()+150
    while time.monotonic()<end:
        m=meta()
        if kind=='agent':
            if m['State']['Running'] and m['RestartCount']==0:run(['docker','exec',container,'python','-c','from nemo_agent.analysis_graph import create_analysis_graph;from nemo_agent.trusted_templates import build_template;assert build_template("binary_first",{"difficulty":"EASY"},"a")==build_template("binary_first",{"difficulty":"EASY"},"b")']);return
        else:
            try:
                ip=next(iter(m['NetworkSettings']['Networks'].values()))['IPAddress']
                with urllib.request.urlopen('http://'+ip+':9104/actuator/health/readiness',timeout=3) as response:
                    if json.load(response).get('status')=='UP':return
            except Exception:pass
        time.sleep(2)
    raise RuntimeError('Readiness exceeded')
assert kind in ('judge','agent')
if kind=='judge' and meta()['Config']['Image']=='nemo-oj/judge-service:trusted-artifacts-20261014':
    base=meta()['Config']['Image'];image='nemo-oj/judge-service:trusted-artifacts-final-20261014'
assert meta()['Config']['Image']==base
if action=='prepare':
    manifest=json.loads((stage/(kind+'-manifest.json')).read_text())
    with tarfile.open(str(stage/(kind+'.tgz'))) as archive:
        entries=archive.getmembers();assert all(m.isfile() for m in entries) and len(entries)==len(manifest) and {m.name for m in entries}==set(manifest)
        files={m.name:archive.extractfile(m).read() for m in entries}
    assert all(hashlib.sha256(v).hexdigest()==manifest[k] for k,v in files.items())
    folder=stage/(kind+'-image');folder.mkdir(exist_ok=True)
    if kind=='agent':
        assert set(files)=={'trusted_templates.py','redblack_topics.py'}
        for name,data in files.items():(folder/name).write_bytes(data)
        dockerfile='FROM '+base+'\n'+''.join('COPY --chown=10001:10001 '+n+' /app/nemo_agent/'+n+'\n' for n in files)
    else:
        import re
        assert all(n=='trusted-validation-contracts.json' or re.fullmatch(r'com/nemo/OJbackendjudgeservice/judge/(PrivateProblemValidationService|TrustedValidationArtifacts)(\$[A-Za-z0-9]+)?\.class',n) for n in files)
        edits={'BOOT-INF/classes/'+k:v for k,v in files.items()};original=run(['docker','exec',container,'cat','/app/service.jar']);out=io.BytesIO()
        with zipfile.ZipFile(io.BytesIO(original)) as old,zipfile.ZipFile(out,'w') as new:
            for item in old.infolist():new.writestr(copy.copy(item),edits.get(item.filename,old.read(item)))
            for name,data in edits.items():
                if name not in old.namelist():new.writestr(name,data)
        with zipfile.ZipFile(io.BytesIO(original)) as old,zipfile.ZipFile(io.BytesIO(out.getvalue())) as new:assert all(old.read(n)==new.read(n) for n in old.namelist() if n not in edits)
        (folder/'service.jar').write_bytes(out.getvalue());dockerfile='FROM '+base+'\nCOPY service.jar /app/service.jar\n'
    (folder/'Dockerfile').write_text(dockerfile);run(['docker','build','--network=none','--pull=false','-t',image,str(folder)],240)
    print(json.dumps({'prepared':True,'image':image,'patchedFiles':len(files),'unrelatedFilesUnchanged':True}));sys.exit(0)
assert action=='deploy';old=meta();env=Path('/srv/nemo-ai/deploy/.env.agent' if kind=='agent' else '/srv/nemo-oj/deploy/.env');assert env.stat().st_mode&0o777==0o600
args=['docker','compose','--project-name','nemo-ai' if kind=='agent' else 'nemo-oj','--env-file',str(env)]
for f in dict.fromkeys(old['Config']['Labels']['com.docker.compose.project.config_files'].split(',')):assert Path(f).parent==env.parent;args+=['-f',f]
before=json.loads(run(args+['config','--format','json']));original=env.read_bytes();variable='NEMO_AGENT_IMAGE' if kind=='agent' else 'NEMO_JUDGE_IMAGE';service='nemo-agent' if kind=='agent' else 'judge-service'
lines=original.decode().splitlines();assert sum(l.startswith(variable+'=') for l in lines)==1
updated='\n'.join(variable+'='+image if l.startswith(variable+'=') else l for l in lines)+'\n'
overlay=env.parent/'compose.trusted-artifacts.yml';overlay_old=overlay.read_bytes() if overlay.exists() else None
active=args.copy()
if kind=='judge':
    sys.path.insert(0,'/usr/local/lib/nemo-ops');from common import mysql
    assert mysql("SELECT COUNT(*) FROM ai_task WHERE status IN ('QUEUED','RUNNING')")=='0'
    assert mysql('SELECT COUNT(*) FROM question_submit WHERE isDelete=0 AND status IN (0,1)')=='0'
    cache=Path('/srv/nemo-oj/trusted-validation-cache');cache.mkdir(exist_ok=True);os.chown(str(cache),10001,10001);os.chmod(str(cache),0o700)
    atomic(overlay,(stage/'compose.trusted-artifacts.yml').read_bytes(),0o644)
    if str(overlay) not in active:active+=['-f',str(overlay)]
start=time.monotonic()
try:
    atomic(env,updated.encode());actual=json.loads(run(active+['config','--format','json']));normalized=copy.deepcopy(actual);normalized['services'][service]['image']=before['services'][service]['image']
    if kind=='judge':
        s=normalized['services'][service]
        assert s['environment'].pop('AI_TRUSTED_VALIDATION_CACHE_ENABLED')=='true'
        assert s['environment'].pop('AI_TRUSTED_VALIDATION_CACHE_PATH')=='/var/lib/nemo-validation'
        for k in ('AI_TRUSTED_VALIDATION_CACHE_ENABLED','AI_TRUSTED_VALIDATION_CACHE_PATH'):
            if k in before['services'][service]['environment']:s['environment'][k]=before['services'][service]['environment'][k]
        mounts=s.get('volumes',[]);added=[v for v in mounts if v['target']=='/var/lib/nemo-validation'];assert len(added)==1 and added[0]['source']=='/srv/nemo-oj/trusted-validation-cache' and not added[0].get('read_only',False)
        s['volumes']=[v for v in mounts if v not in added or v in before['services'][service].get('volumes',[])]
        if not s['volumes'] and 'volumes' not in before['services'][service]:s.pop('volumes')
    assert normalized==before,'Unexpected configuration change'
    run(active+['up','-d','--no-deps','--pull','never',service]);time.sleep(8 if kind=='agent' else 1);ready()
    report={'passed':True,'image':image,'secondsUntilReady':round(time.monotonic()-start,3),'backupsCreated':False};atomic(stage/(kind+'-release.json'),json.dumps(report).encode());print(json.dumps(report),flush=True)
except BaseException:
    atomic(env,original)
    if kind=='judge':
        if overlay_old is None:overlay.unlink()
        else:atomic(overlay,overlay_old,0o644)
    run(args+['up','-d','--no-deps','--pull','never',service]);ready();raise RuntimeError('Artifact release failed; previous image restored') from None
