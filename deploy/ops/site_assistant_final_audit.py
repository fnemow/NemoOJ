import hashlib,json,subprocess,sys,urllib.request
from pathlib import Path
sys.path.insert(0,'/usr/local/lib/nemo-ops');from common import mysql,atomic_json
stage=Path('/home/ubuntu/nemo-site-assistant-20261014')
manifest=json.loads((stage/'frontend-manifest.json').read_text());report={}
with urllib.request.urlopen('https://fw-nemo.cn/',timeout=20) as response:
    page=response.read();assert hashlib.sha256(page).hexdigest()==manifest['index.html'];report['publicHomepageMatchesRelease']=True
with urllib.request.urlopen('https://fw-nemo.cn/api/question/assistant/guides',timeout=20) as response:
    assert len(json.load(response)['data'])==18;report['publicGuides']=18
command=['docker','inspect','nemo-oj-question-service-1','nemo-oj-user-service-1','nemo-oj-judge-service-1','nemo-oj-sandbox-1','nemo-oj-gateway-1','nemo-oj-proxy-1']
r=subprocess.run(command,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=20);assert r.returncode==0
containers=json.loads(r.stdout);assert all(m['State']['Running'] and m['RestartCount']==0 for m in containers)
report['coreContainersRunning']=len(containers);report['activeAiTasks']=int(mysql("SELECT COUNT(*) FROM ai_task WHERE status IN ('QUEUED','RUNNING')"))
report['activeSubmissions']=int(mysql('SELECT COUNT(*) FROM question_submit WHERE isDelete=0 AND status IN (0,1)'))
report['registrationStillClosed']='REGISTRATION_ENABLED=false' in Path('/srv/nemo-oj/deploy/.env').read_text().splitlines()
assert report['registrationStillClosed']
report['liveAcceptance']=json.loads((stage/'live-acceptance.json').read_text())['passed'];assert report['liveAcceptance']
report['passed']=True;atomic_json(stage/'final-audit.json',report);print(json.dumps(report),flush=True)
