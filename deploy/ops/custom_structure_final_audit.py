"""Sanitized image/manifest audit. No environment or application data is exported."""
import hashlib,io,json,subprocess,sys,zipfile
from pathlib import Path
stage=Path(sys.argv[1]);agent='--agent' in sys.argv
def run(args):
    p=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=30)
    if p.returncode:raise RuntimeError('Audit command failed; output withheld')
    return p.stdout
report={'passed':False,'services':[]}
for kind in ('agent',) if agent else ('judge','question'):
    name='nemo-ai-nemo-agent-1' if agent else 'nemo-oj-'+kind+'-service-1'
    m=json.loads(run(['docker','inspect',name]))[0];manifest=json.loads((stage/(kind+'-manifest.json')).read_text())
    assert m['State']['Running'] and m['RestartCount']==0
    if agent:
        for name,sha in manifest.items():assert hashlib.sha256(run(['docker','exec',m['Id'],'cat','/app/nemo_agent/'+name])).hexdigest()==sha
    else:
        with zipfile.ZipFile(io.BytesIO(run(['docker','exec',m['Id'],'cat','/app/service.jar']))) as jar:
            with zipfile.ZipFile(io.BytesIO(jar.read('BOOT-INF/lib/OJ-backend-model-0.0.1-SNAPSHOT.jar'))) as model:
                for path,sha in manifest.items():
                    data=model.read(path[6:]) if path.startswith('model/') else jar.read('BOOT-INF/classes/'+path[8:]) if path.startswith('classes/') else jar.read('BOOT-INF/lib/'+path[8:])
                    assert hashlib.sha256(data).hexdigest()==sha
    report['services'].append({'service':kind,'image':m['Config']['Image'],'restartCount':m['RestartCount'],'sourceManifestMatches':True})
if not agent:
    root=Path('/srv/nemo-oj/nemo-frontend/dist');manifest=json.loads((stage/'frontend-manifest.json').read_text())
    assert all(hashlib.sha256((root/n).read_bytes()).hexdigest()==sha for n,sha in manifest.items());report['frontendManifestMatches']=True
    sys.path.insert(0,'/usr/local/lib/nemo-ops');from common import mysql
    report['activeAiTasks']=int(mysql("SELECT COUNT(*) FROM ai_task WHERE status IN ('QUEUED','RUNNING')"))
    report['activeSubmissions']=int(mysql('SELECT COUNT(*) FROM question_submit WHERE isDelete=0 AND status IN (0,1)'))
    report['residualSandboxContainers']=len(run(['docker','ps','-aq','--filter','label=nemo-oj.sandbox=true']).split())
report['passed']=True
(stage/('agent-audit.json' if agent else 'core-audit.json')).write_text(json.dumps(report))
print(json.dumps(report),flush=True)
