import json,subprocess,sys
from pathlib import Path
from release_control import remote
ROOT=Path(__file__).resolve().parents[2];key=Path('C:/Users/nemo/.ssh/id_ed25519_nemooj')
kind=sys.argv[1];action=sys.argv[2]
assert kind in ('question','agent','frontend') and action in ('prepare','deploy','evidence')
host='root@123.57.239.129' if kind=='agent' else 'ubuntu@43.142.118.220'
stage='/root/nemo-site-assistant-20261014' if kind=='agent' else '/home/ubuntu/nemo-site-assistant-20261014'
if action=='evidence':
    assert kind=='question'
    script='import json;from pathlib import Path;root=Path('+repr(stage)+');print(json.dumps({name:json.loads((root/name).read_text()) for name in ["live-acceptance.json","final-audit.json"]}))'
    reports=json.loads(remote(host,key,script))
    for name,value in reports.items():(ROOT/'build/site-assistant-20261014'/name).write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8')
    print('Sanitized acceptance summaries saved');sys.exit(0)
if action=='prepare' or kind=='frontend':
    setup='from pathlib import Path;Path('+repr(stage)+').mkdir(exist_ok=True);'
    if kind!='agent':setup+='import shutil;shutil.chown('+repr(stage)+',user="ubuntu",group="ubuntu");'
    print(remote(host,key,setup+'print("stage-ready")').strip())
    names=['agent.tgz','agent-manifest.json'] if kind=='agent' else ['classes.tgz','classes-manifest.json'] if kind=='question' else ['frontend.tar.gz','frontend-manifest.json']
    files=[ROOT/'build/site-assistant-20261014'/n for n in names]+[ROOT/'deploy/ops/site_assistant_release.py']
    result=subprocess.run(['scp','-q','-i',str(key),'-o','BatchMode=yes','-o','ConnectTimeout=15',*[str(p) for p in files],host+':'+stage+'/'],capture_output=True,timeout=180)
    if result.returncode:raise RuntimeError('Artifact upload failed; output withheld')
if action=='deploy' and kind=='agent':
    audit=json.loads(remote('ubuntu@43.142.118.220',key,(ROOT/'deploy/ops/site_assistant_audit.py').read_text(encoding='utf-8')))
    assert audit['activeAiTasks']==0 and audit['activeSubmissions']==0
script='import runpy,sys;sys.argv=['+repr(stage+'/site_assistant_release.py')+','+repr(stage)+','+repr(action)+','+repr(kind)+'];runpy.run_path(sys.argv[0],run_name="__main__")'
print(remote(host,key,script,timeout=420).strip())
