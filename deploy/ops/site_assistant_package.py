"""Focused release artifacts; excludes secrets and unrelated backend classes."""
import hashlib,io,json,tarfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'build/site-assistant-20261014';OUT.mkdir(parents=True,exist_ok=True)
def bundle(filename,files):
    manifest={name:hashlib.sha256(data).hexdigest() for name,data in files.items()}
    with tarfile.open(OUT/filename,'w:gz') as archive:
        for name,data in files.items():
            entry=tarfile.TarInfo(name);entry.size=len(data);archive.addfile(entry,io.BytesIO(data))
    return manifest
base=ROOT/'OJ-backend-microservice/OJ-backend-question-service/target/classes'
prefix='com/nemo/OJbackendquestionservice/'
allowed=['controller/AgentInternalController','controller/AiController','controller/SiteAssistantController','service/SiteHelpPolicy']
classes={}
for name in allowed:
    path=base/(prefix+name+'.class');assert path.exists()
    for p in [path,*path.parent.glob(path.stem+'$*.class')]:classes[p.relative_to(base).as_posix()]=p.read_bytes()
(OUT/'classes-manifest.json').write_text(json.dumps(bundle('classes.tgz',classes)),encoding='utf-8')
agent={name:(ROOT/'nemo-agent/nemo_agent'/name).read_bytes() for name in ['analysis_graph.py','schemas.py','worker.py']}
(OUT/'agent-manifest.json').write_text(json.dumps({'baseAnalysisSha256':'5ca69bade10c8a0659914e3b7569021cdbebb013a142cc58fabfff980fdb0a57','files':bundle('agent.tgz',agent)}),encoding='utf-8')
dist=ROOT/'nemo-frontend/dist';files={p.relative_to(dist).as_posix():p.read_bytes() for p in dist.rglob('*') if p.is_file()}
(OUT/'frontend-manifest.json').write_text(json.dumps(bundle('frontend.tar.gz',files)),encoding='utf-8')
print(json.dumps({'classes':len(classes),'agentFiles':len(agent),'frontendFiles':len(files),'artifactDirectory':str(OUT)}))
