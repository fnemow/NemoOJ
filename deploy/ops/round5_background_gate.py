"""Tighten background quiet-window only, with effective-config diff and rollback."""
import copy,json,subprocess,sys,time,urllib.request
from pathlib import Path
sys.path.insert(0,'/usr/local/lib/nemo-ops');from common import mysql,atomic_json
stage=Path('/home/ubuntu/nemo-validation-round5-20261013')
def run(args):
    p=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=180)
    if p.returncode:raise RuntimeError('Gate command failed; output withheld')
    return p.stdout
def meta():return json.loads(run(['docker','inspect','nemo-oj-sandbox-1']))[0]
old=meta();labels=old['Config']['Labels'];env=Path('/srv/nemo-oj/deploy/.env');command=['docker','compose','--project-name','nemo-oj','--env-file',str(env)]
for f in dict.fromkeys(labels['com.docker.compose.project.config_files'].split(',')):command+=['-f',f]
assert mysql("SELECT COUNT(*) FROM ai_task WHERE status IN ('QUEUED','RUNNING')")=='0'
assert mysql("SELECT COUNT(*) FROM question_submit WHERE status IN (0,1) AND isDelete=0")=='0'
assert not run(['docker','ps','-aq','--filter','label=nemo-oj.sandbox=true']).strip()
before=json.loads(run(command+['config','--format','json']));overlay=env.parent/'compose.validation-prewarm.yml';original=overlay.read_text()
try:
    overlay.write_text('services:\n  sandbox:\n    environment:\n      SANDBOX_PREWARM_ENABLED: "true"\n      SANDBOX_BACKGROUND_IDLE_GRACE_MS: "5000"\n')
    after=json.loads(run(command+['config','--format','json']));expected=copy.deepcopy(before);expected['services']['sandbox']['environment']['SANDBOX_BACKGROUND_IDLE_GRACE_MS']='5000';assert expected==after
    run(command+['up','-d','--no-deps','--pull','never','sandbox'])
    deadline=time.monotonic()+180
    while time.monotonic()<deadline:
        m=meta();values=dict(v.split('=',1) for v in m['Config']['Env']);ip=next(iter(m['NetworkSettings']['Networks'].values()))['IPAddress']
        try:
            response=json.load(urllib.request.urlopen('http://'+ip+':'+values.get('MANAGEMENT_PORT','9190')+'/actuator/health/readiness',timeout=4))
            if response['status']=='UP':break
        except Exception:pass
        time.sleep(3)
    else:raise RuntimeError('Gate readiness failed')
    assert values['SANDBOX_BACKGROUND_IDLE_GRACE_MS']=='5000' and m['Config']['Image']==old['Config']['Image']
    report={'passed':True,'idleGraceBeforeMs':before['services']['sandbox']['environment'].get('SANDBOX_BACKGROUND_IDLE_GRACE_MS'),'idleGraceAfterMs':5000,'onlyOneEnvironmentValueChanged':True,'prewarmEnabled':True}
    atomic_json(stage/'background-gate.json',report);print(json.dumps(report))
except BaseException:
    overlay.write_text(original);run(command+['up','-d','--no-deps','--pull','never','sandbox']);raise RuntimeError('Gate adjustment rolled back') from None
