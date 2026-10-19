"""Package narrowly scoped source changes; run sanitized deployment helpers."""
import hashlib,io,json,subprocess,sys,tarfile
from pathlib import Path
from release_control import remote
ROOT=Path(__file__).resolve().parents[2];out=ROOT/'build/custom-structures-20261014';out.mkdir(exist_ok=True)
key=Path('C:/Users/nemo/.ssh/id_ed25519_nemooj');kind=sys.argv[1];action=sys.argv[2]
host='root@123.57.239.129' if kind=='agent' else 'ubuntu@43.142.118.220'
stage='/root/nemo-custom-structures-20261014' if kind=='agent' else '/home/ubuntu/nemo-custom-structures-20261014'
if action=='prepare':
    files={}
    if kind=='agent':files={n:(ROOT/'nemo-agent/nemo_agent'/n).read_bytes() for n in ('schemas.py','custom_structures.py','private_generation.py','generation_topic_policy.py','analysis_graph.py')}
    else:
        modules={'model':['model/dto/question/'+n for n in ('ProblemSpec','CustomStructureSpec','CustomStructureContract')]+['model/vo/QuestionVO','model/execution/LanguageCompatibility']}
        if kind=='judge':modules['judge-service']=['judge/harness/'+n for n in ('CustomNodeGenerator','JavaHarnessGenerator','PythonHarnessGenerator','CppHarnessGenerator','CanonicalPlainProtocol','FunctionResultValidator')]+['judge/mode/FunctionJudgeModeAdapter','judge/PrivateProblemValidationService']
        else:modules['question-service']=['service/impl/QuestionServiceImpl','service/LanguageCapabilityService','service/PrivateValidationJobs','controller/PrivateProblemController']
        for module,names in modules.items():
            base=ROOT/'OJ-backend-microservice'/('OJ-backend-'+module)/'target/classes'
            prefix='com/nemo/'+('OJbackendmodel' if module=='model' else 'OJbackendjudgeservice' if module=='judge-service' else 'OJbackendquestionservice')+'/'
            for name in names:
                path=base/(prefix+name)
                for p in [path.with_suffix('.class'),*path.parent.glob(path.name+'$*.class')]:files[('model/' if module=='model' else 'classes/')+p.relative_to(base).as_posix()]=p.read_bytes()
        if kind=='judge':
            base=ROOT/'OJ-backend-microservice/OJ-backend-judge-service/target/classes'
            for n in ('custom-codec.java','custom-codec.py'):files['classes/harness/'+n]=(base/'harness'/n).read_bytes()
        if kind=='question':
            import zipfile
            jar=ROOT/'OJ-backend-microservice/OJ-backend-question-service/target/OJ-backend-question-service-0.0.1-SNAPSHOT.jar'
            with zipfile.ZipFile(jar) as archive:
                libraries=[n for n in archive.namelist() if n.startswith('BOOT-INF/lib/gson-') and n.endswith('.jar')]
                assert len(libraries)==1
                files['library/'+Path(libraries[0]).name]=archive.read(libraries[0])
    with tarfile.open(out/(kind+'.tgz'),'w:gz') as archive:
        for n,data in files.items():entry=tarfile.TarInfo(n);entry.size=len(data);archive.addfile(entry,io.BytesIO(data))
    (out/(kind+'-manifest.json')).write_text(json.dumps({n:hashlib.sha256(d).hexdigest() for n,d in files.items()}),encoding='utf-8')
    script='from pathlib import Path;Path('+repr(stage)+').mkdir(exist_ok=True);'
    if kind!='agent':script+='import shutil;shutil.chown('+repr(stage)+',user="ubuntu",group="ubuntu");'
    remote(host,key,script+'print("stage-ready")')
    paths=[out/(kind+'.tgz'),out/(kind+'-manifest.json'),ROOT/'deploy/ops/custom_structure_release.py']
    result=subprocess.run(['scp','-q','-i',str(key),'-o','BatchMode=yes','-o','ConnectTimeout=15',*[str(p) for p in paths],host+':'+stage+'/'],capture_output=True,timeout=180)
    if result.returncode:raise RuntimeError('Custom structure upload failed')
if action=='deploy' and kind=='agent':
    audit=json.loads(remote('ubuntu@43.142.118.220',key,(ROOT/'deploy/ops/site_assistant_audit.py').read_text(encoding='utf-8')))
    assert audit['activeAiTasks']==0 and audit['activeSubmissions']==0
script='import runpy,sys;sys.argv=['+repr(stage+'/custom_structure_release.py')+','+repr(stage)+','+repr(kind)+','+repr(action)+'];runpy.run_path(sys.argv[0],run_name="__main__")'
print(remote(host,key,script,timeout=420).strip())
