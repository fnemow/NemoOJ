import io,json,hashlib,subprocess,sys,tarfile
from pathlib import Path
from release_control import remote
ROOT=Path(__file__).resolve().parents[2];key=Path('C:/Users/nemo/.ssh/id_ed25519_nemooj');kind=sys.argv[1];action=sys.argv[2]
out=ROOT/'build/trusted-artifacts-20261014';host='root@123.57.239.129' if kind=='agent' else 'ubuntu@43.142.118.220';stage='/root/nemo-artifacts-20261014' if kind=='agent' else '/home/ubuntu/nemo-artifacts-20261014'
if action=='prepare':
    if kind=='agent':files={n:(ROOT/'nemo-agent/nemo_agent'/n).read_bytes() for n in ('trusted_templates.py','redblack_topics.py')}
    else:
        base=ROOT/'OJ-backend-microservice/OJ-backend-judge-service/target/classes';files={}
        for name in ('PrivateProblemValidationService','TrustedValidationArtifacts'):
            parent=base/'com/nemo/OJbackendjudgeservice/judge';paths=[parent/(name+'.class'),*parent.glob(name+'$*.class')]
            for p in paths:files[p.relative_to(base).as_posix()]=p.read_bytes()
        files['trusted-validation-contracts.json']=(base/'trusted-validation-contracts.json').read_bytes()
    with tarfile.open(out/(kind+'.tgz'),'w:gz') as archive:
        for name,data in files.items():entry=tarfile.TarInfo(name);entry.size=len(data);archive.addfile(entry,io.BytesIO(data))
    (out/(kind+'-manifest.json')).write_text(json.dumps({n:hashlib.sha256(d).hexdigest() for n,d in files.items()}),encoding='utf-8')
    script='from pathlib import Path;Path('+repr(stage)+').mkdir(exist_ok=True);'
    if kind=='judge':script+='import shutil;shutil.chown('+repr(stage)+',user="ubuntu",group="ubuntu");'
    remote(host,key,script+'print("stage-ready")')
    paths=[out/(kind+'.tgz'),out/(kind+'-manifest.json'),ROOT/'deploy/ops/trusted_artifact_release.py']
    if kind=='judge':paths+=[ROOT/'deploy/compose.trusted-artifacts.yml',out/'prevalidation-requests.json']
    result=subprocess.run(['scp','-q','-i',str(key),'-o','BatchMode=yes','-o','ConnectTimeout=15',*[str(p) for p in paths],host+':'+stage+'/'],capture_output=True,timeout=180)
    if result.returncode:raise RuntimeError('Artifact upload failed')
if action=='deploy' and kind=='agent':
    audit=json.loads(remote('ubuntu@43.142.118.220',key,(ROOT/'deploy/ops/site_assistant_audit.py').read_text(encoding='utf-8')));assert audit['activeAiTasks']==0 and audit['activeSubmissions']==0
script='import runpy,sys;sys.argv=['+repr(stage+'/trusted_artifact_release.py')+','+repr(stage)+','+repr(kind)+','+repr(action)+'];runpy.run_path(sys.argv[0],run_name="__main__")'
print(remote(host,key,script,timeout=420).strip())
