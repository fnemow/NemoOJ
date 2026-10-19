"""Idle-only audited prevalidation with cold/warm timing; no provider calls or publication."""
import copy,json,subprocess,sys,time,urllib.request
from pathlib import Path
sys.path.insert(0,'/usr/local/lib/nemo-ops');from common import mysql,atomic_json
stage=Path(sys.argv[1]);requests=json.loads((stage/'prevalidation-requests.json').read_text());report={'samples':[],'passed':False}
if (stage/'prewarm.json').exists():report=json.loads((stage/'prewarm.json').read_text())
def meta(service):return json.loads(subprocess.check_output(['docker','inspect','nemo-oj-'+service+'-1']))[0]
def counts():
    m=meta('sandbox');e=dict(x.split('=',1) for x in m['Config']['Env']);ip=next(iter(m['NetworkSettings']['Networks'].values()))['IPAddress']
    data=urllib.request.urlopen('http://'+ip+':'+e.get('MANAGEMENT_PORT','9190')+'/actuator/prometheus',timeout=10).read().decode()
    return sum(float(line.split()[-1]) for line in data.splitlines() if line.startswith('nemo_sandbox_stage_duration_seconds_count{') and ('stage="compile"' in line or 'stage="run"' in line))
def call(body):
    m=meta('judge-service');e=dict(x.split('=',1) for x in m['Config']['Env']);ip=next(iter(m['NetworkSettings']['Networks'].values()))['IPAddress']
    req=urllib.request.Request('http://'+ip+':8104/api/judge/inner/private-problem/validate',data=json.dumps(body).encode(),headers={'Content-Type':'application/json','X-Internal-Token':e['INTERNAL_AUTH_SECRET']})
    try:
        with urllib.request.urlopen(req,timeout=480) as response:value=json.load(response)
    except Exception:raise RuntimeError('Prevalidation request failed; diagnostics withheld') from None
    if 'code' in value:assert value['code']==0,'Prevalidation rejected'
    return value.get('data',value)
for name,body in requests.items():
    if '--refresh' not in sys.argv and any(row['template']==name for row in report['samples']):continue
    while mysql("SELECT COUNT(*) FROM ai_task WHERE status IN ('QUEUED','RUNNING')")!='0' or mysql('SELECT COUNT(*) FROM question_submit WHERE isDelete=0 AND status IN (0,1)')!='0':
        print(json.dumps({'waitingForIdle':True}),flush=True);time.sleep(10)
    start=time.monotonic();cold=call(copy.deepcopy(body));cold_seconds=time.monotonic()-start
    if not cold.get('passed'):
        row={'template':name,'passed':False,'category':cold.get('category'),'combination':cold.get('combination')};report['failed']=row;atomic_json(stage/'prewarm.json',report);print(json.dumps(row),flush=True);raise RuntimeError('Audited fixture failed validation')
    assert cold.get('oracleValidated') and cold.get('runtimeValidated') and len(cold['combinations'])==6
    before=counts();start=time.monotonic();warm=call(copy.deepcopy(body));warm_seconds=time.monotonic()-start;after=counts()
    assert warm.get('completeProofReused') and warm.get('passed') and before==after,'Warm cache failed or sandbox unexpectedly executed'
    assert warm['functionCases']==cold['functionCases'] and warm['acmCases']==cold['acmCases'] and warm['referenceSolutions']==cold['referenceSolutions']
    row={'template':name,'passed':True,'coldSeconds':round(cold_seconds,3),'coldAlreadyCached':cold.get('completeProofReused',False),'warmSeconds':round(warm_seconds,3),'warmSandboxExecutions':int(after-before),'combinations':6,'caseCount':cold['caseCount']}
    report['samples']=[r for r in report['samples'] if r['template']!=name]+[row];atomic_json(stage/'prewarm.json',report);print(json.dumps(row),flush=True)
report['passed']=len(report['samples'])==len(requests);report['contracts']=len(requests);report['modelCalls']=0
report['residualContainers']=len(subprocess.check_output(['docker','ps','-aq','--filter','label=nemo-oj.sandbox=true']).split());assert report['residualContainers']==0
atomic_json(stage/'prewarm.json',report);print(json.dumps({'passed':report['passed'],'contracts':len(requests),'residualContainers':report['residualContainers']}),flush=True)
