"""Export only redacted release reports, never payloads, configs or database contents."""
import json,os,pwd
from pathlib import Path
stage=Path('/home/ubuntu/nemo-redblack-site-20261014');out=stage/'export'
out.mkdir(exist_ok=True,mode=0o700);user=pwd.getpwnam('ubuntu');os.chown(out,user.pw_uid,user.pw_gid)
names=('frontend-release','live-easy','live-medium','live-hard','formal','final-audit')
def inspect(value):
    if isinstance(value,dict):
        assert not set(value).intersection({'password','apiKey','api_key','code','source','referenceSolutions','token'})
        for child in value.values():inspect(child)
    elif isinstance(value,list):
        for child in value:inspect(child)
for name in names:
    content=(stage/(name+'.json')).read_bytes();inspect(json.loads(content))
    target=out/(name+'.json');target.write_bytes(content);os.chmod(target,0o600);os.chown(target,user.pw_uid,user.pw_gid)
print(json.dumps({'exportedReports':len(names)}))
