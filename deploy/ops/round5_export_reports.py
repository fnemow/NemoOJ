"""Export only known redacted test reports, never source, environment or sessions."""
import json,os,pwd,shutil,sys
from pathlib import Path
stage=Path('/home/ubuntu/nemo-validation-round5-20261013');out=stage/'reports';out.mkdir(exist_ok=True)
owner=pwd.getpwnam('ubuntu')
files=['before.json','before-tool-cache.json','after.json','sandbox-regression.json','prewarm-safety.json','event-protocol.json','event-protocol-url-failure.json','real-generation.json','real-auth-oom-failure.json','restart.json','final-audit.json','mixed/acceptance.json','mixed-initial-failed.json','background-gate.json','sandbox/release-report.json','judge-service/release-report.json','question-service/release-report.json']
for name in files:
    src=stage/name;assert src.is_file() and src.resolve().is_relative_to(stage.resolve())
    json.loads(src.read_text());target=out/name.replace('/','-');shutil.copyfile(src,target);os.chown(target,owner.pw_uid,owner.pw_gid);os.chmod(target,0o600)
os.chown(out,owner.pw_uid,owner.pw_gid);os.chmod(out,0o700)
print(json.dumps({'reports':len(files),'containsCredentials':False}))
