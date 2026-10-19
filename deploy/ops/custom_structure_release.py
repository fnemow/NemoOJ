"""Patch reviewed classes into current images; preserve config and unrelated entries."""
import copy, hashlib, io, json, os, re, subprocess, sys, tarfile, tempfile, time, urllib.request, zipfile
from pathlib import Path

stage=Path(sys.argv[1]);kind=sys.argv[2];action=sys.argv[3]
assert kind in ('judge','question','agent')
service={'judge':'judge-service','question':'question-service','agent':'nemo-agent'}[kind]
container=('nemo-ai-' if kind=='agent' else 'nemo-oj-')+service+'-1'
base={'judge':'nemo-oj/judge-service:trusted-artifacts-final-20261014','question':'nemo-oj/question-service:site-assistant-20261014','agent':'nemo-oj/agent:trusted-artifacts-20261014'}[kind]
image='nemo-oj/'+('agent' if kind=='agent' else service)+':custom-structures-20261014'

def run(args,timeout=180):
    p=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=timeout)
    if p.returncode:raise RuntimeError('Release command failed; output withheld: '+args[0])
    return p.stdout

def meta():return json.loads(run(['docker','inspect',container]))[0]

def atomic(path,data,mode=0o600):
    fd,tmp=tempfile.mkstemp(dir=str(path.parent));os.fchmod(fd,mode)
    with os.fdopen(fd,'wb') as f:f.write(data);f.flush();os.fsync(f.fileno())
    os.replace(tmp,str(path))

def patch(data,edits):
    result=io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(data)) as old,zipfile.ZipFile(result,'w') as new:
        for entry in old.infolist():new.writestr(copy.copy(entry),edits.get(entry.filename,old.read(entry)))
        for name,value in edits.items():
            if name not in old.namelist():new.writestr(name,value)
    with zipfile.ZipFile(io.BytesIO(data)) as old,zipfile.ZipFile(io.BytesIO(result.getvalue())) as new:
        assert all(old.read(n)==new.read(n) for n in old.namelist() if n not in edits)
    return result.getvalue()

def ready():
    until=time.monotonic()+150
    while time.monotonic()<until:
        m=meta()
        if kind=='agent':
            if m['State']['Running'] and m['RestartCount']==0:
                run(['docker','exec',container,'python','-c','from nemo_agent.analysis_graph import create_analysis_graph;from nemo_agent.schemas import CanonicalSignature;from nemo_agent.custom_structures import validate_graph;assert CanonicalSignature(methodName="solve",parameters=[{"name":"x","type":"int"}],returnType="int").dataStructures==[]'])
                return
        else:
            try:
                ip=next(iter(m['NetworkSettings']['Networks'].values()))['IPAddress']
                with urllib.request.urlopen('http://'+ip+':'+('9104' if kind=='judge' else '9103')+'/actuator/health/readiness',timeout=3) as r:
                    if json.load(r).get('status')=='UP':return
            except Exception:pass
        time.sleep(2)
    raise RuntimeError('Readiness deadline exceeded')

if kind=='agent' and meta()['Config']['Image']==image:
    base=image;image='nemo-oj/agent:custom-structures-final-20261014'
elif kind=='agent' and meta()['Config']['Image']=='nemo-oj/agent:custom-structures-final-20261014':
    base=meta()['Config']['Image'];image='nemo-oj/agent:custom-structures-canonical-20261014'
elif kind in ('judge','question') and meta()['Config']['Image']==image:
    base=image;image='nemo-oj/'+service+':custom-structures-final-20261014'
assert meta()['Config']['Image']==base
if action=='prepare':
    manifest=json.loads((stage/(kind+'-manifest.json')).read_text())
    with tarfile.open(str(stage/(kind+'.tgz'))) as archive:
        entries=archive.getmembers();assert len(entries)==len(manifest) and all(m.isfile() for m in entries) and {m.name for m in entries}==set(manifest)
        files={m.name:archive.extractfile(m).read() for m in entries}
    assert all(hashlib.sha256(v).hexdigest()==manifest[n] for n,v in files.items())
    folder=stage/(kind+'-image');folder.mkdir(exist_ok=True)
    if kind=='agent':
        assert set(files)=={'schemas.py','custom_structures.py','private_generation.py','generation_topic_policy.py','analysis_graph.py'}
        for name,data in files.items():(folder/name).write_bytes(data)
        dockerfile='FROM '+base+'\n'+''.join('COPY --chown=10001:10001 '+n+' /app/nemo_agent/'+n+'\n' for n in sorted(files))
    else:
        model_pattern=r'model/com/nemo/OJbackendmodel/model/(dto/question/(ProblemSpec|CustomStructureSpec|CustomStructureContract)|vo/QuestionVO|execution/LanguageCompatibility)(\$[A-Za-z0-9]+)?\.class'
        own_pattern={'judge':r'classes/(com/nemo/OJbackendjudgeservice/judge/(harness/(CustomNodeGenerator|JavaHarnessGenerator|PythonHarnessGenerator|CppHarnessGenerator|CanonicalPlainProtocol|FunctionResultValidator)|mode/FunctionJudgeModeAdapter|PrivateProblemValidationService)(\$[A-Za-z0-9]+)?\.class|harness/custom-codec\.(java|py))','question':r'classes/com/nemo/OJbackendquestionservice/(service/(impl/QuestionServiceImpl|LanguageCapabilityService|PrivateValidationJobs)|controller/PrivateProblemController)(\$[A-Za-z0-9]+)?\.class'}[kind]
        assert all(re.fullmatch(model_pattern,n) or re.fullmatch(own_pattern,n) or (kind=='question' and re.fullmatch(r'library/gson-[0-9.]+\.jar',n)) for n in files)
        original=run(['docker','exec',container,'cat','/app/service.jar'])
        edits={'BOOT-INF/classes/'+n[len('classes/'):]:v for n,v in files.items() if n.startswith('classes/')}
        library='BOOT-INF/lib/OJ-backend-model-0.0.1-SNAPSHOT.jar'
        with zipfile.ZipFile(io.BytesIO(original)) as jar:
            gson=[n for n in jar.namelist() if n.startswith('BOOT-INF/lib/gson-')]
            if kind=='judge':assert len(gson)==1
            else:
                additions={n.replace('library/','BOOT-INF/lib/',1):v for n,v in files.items() if n.startswith('library/')}
                assert len(additions)==1
                assert not gson or set(gson)==set(additions)
                edits.update(additions)
                if not gson and 'BOOT-INF/classpath.idx' in jar.namelist():
                    edits['BOOT-INF/classpath.idx']=jar.read('BOOT-INF/classpath.idx').rstrip(b'\n')+b'\n'+''.join('- "'+n+'"\n' for n in additions).encode()
            edits[library]=patch(jar.read(library),{n[len('model/'):]:v for n,v in files.items() if n.startswith('model/')})
        (folder/'service.jar').write_bytes(patch(original,edits));dockerfile='FROM '+base+'\nCOPY service.jar /app/service.jar\n'
    (folder/'Dockerfile').write_text(dockerfile)
    run(['docker','build','--network=none','--pull=false','-t',image,str(folder)],240)
    if kind=='agent':run(['docker','run','--rm','--network=none','--memory=256m','--entrypoint=python',image,'-c','from nemo_agent.analysis_graph import create_analysis_graph;from nemo_agent.schemas import CanonicalSignature;assert CanonicalSignature(methodName="solve",parameters=[{"name":"x","type":"int"}],returnType="int").dataStructures==[]'])
    print(json.dumps({'prepared':True,'image':image,'patchedFiles':len(files),'unrelatedFilesUnchanged':True}),flush=True);sys.exit(0)

assert action=='deploy'
old=meta();env=Path('/srv/nemo-ai/deploy/.env.agent' if kind=='agent' else '/srv/nemo-oj/deploy/.env');assert env.stat().st_mode&0o777==0o600
if kind!='agent':
    sys.path.insert(0,'/usr/local/lib/nemo-ops');from common import mysql
    assert mysql("SELECT COUNT(*) FROM ai_task WHERE status IN ('QUEUED','RUNNING')")=='0'
    assert mysql('SELECT COUNT(*) FROM question_submit WHERE isDelete=0 AND status IN (0,1)')=='0'
args=['docker','compose','--project-name','nemo-ai' if kind=='agent' else 'nemo-oj','--env-file',str(env)]
for path in dict.fromkeys(old['Config']['Labels']['com.docker.compose.project.config_files'].split(',')):
    assert Path(path).parent==env.parent;args+=['-f',path]
before=json.loads(run(args+['config','--format','json']));original=env.read_bytes()
variable={'judge':'NEMO_JUDGE_IMAGE','question':'NEMO_QUESTION_IMAGE','agent':'NEMO_AGENT_IMAGE'}[kind]
lines=original.decode().splitlines();assert sum(l.startswith(variable+'=') for l in lines)==1
updated='\n'.join(variable+'='+image if l.startswith(variable+'=') else l for l in lines)+'\n'
start=time.monotonic()
try:
    atomic(env,updated.encode());actual=json.loads(run(args+['config','--format','json']))
    expected=copy.deepcopy(before);expected['services'][service]['image']=image;assert actual==expected
    run(args+['up','-d','--no-deps','--pull','never',service]);time.sleep(8 if kind=='agent' else 1);ready()
    assert dict(e.split('=',1) for e in old['Config']['Env'])==dict(e.split('=',1) for e in meta()['Config']['Env'])
    report={'passed':True,'image':image,'secondsUntilReady':round(time.monotonic()-start,3),'environmentUnchanged':True,'backupsCreated':False}
    atomic(stage/(kind+'-release.json'),json.dumps(report).encode());print(json.dumps(report),flush=True)
except BaseException:
    atomic(env,original);run(args+['up','-d','--no-deps','--pull','never',service]);ready()
    raise RuntimeError('Custom structure release failed; previous image restored') from None
