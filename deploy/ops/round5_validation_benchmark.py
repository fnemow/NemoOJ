"""Matched authored candidate benchmark; no model, credentials or source in output."""
import json,sys,time,subprocess,urllib.request,threading,uuid
from pathlib import Path
sys.path.insert(0,'/usr/local/lib/nemo-ops')
from common import mysql,atomic_json

stage=Path('/home/ubuntu/nemo-validation-round5-20261013')
label=sys.argv[1];assert label in ('before','after','disabled')
fixture=json.loads(Path('/home/ubuntu/nemo-generation-20261011/fixtures.json').read_text())['prefix_matrix']
def meta(service):return json.loads(subprocess.check_output(['docker','inspect','nemo-oj-'+service+'-1'],stderr=subprocess.DEVNULL))[0]
def scrape():
    m=meta('sandbox');env=dict(e.split('=',1) for e in m['Config']['Env']);ip=next(iter(m['NetworkSettings']['Networks'].values()))['IPAddress']
    text=urllib.request.urlopen('http://'+ip+':'+env.get('MANAGEMENT_PORT','9190')+'/actuator/prometheus',timeout=8).read().decode()
    return '\n'.join(l for l in text.splitlines() if l.startswith(('nemo_sandbox_stage_duration_seconds_count','nemo_sandbox_stage_duration_seconds_sum','nemo_sandbox_prefetch_total','nemo_sandbox_container_cleanup_failures_total')))
assert mysql("SELECT COUNT(*) FROM ai_task WHERE status IN ('QUEUED','RUNNING')")=='0'
assert mysql("SELECT COUNT(*) FROM question_submit WHERE status IN (0,1) AND isDelete=0")=='0'
m=meta('judge-service');ip=next(iter(m['NetworkSettings']['Networks'].values()))['IPAddress'];env=dict(e.split('=',1) for e in m['Config']['Env'])
report={'label':label,'sandboxImage':meta('sandbox')['Config']['Image'],'judgeImage':m['Config']['Image'],'modelCalls':0,'samples':[],'beforeMetrics':scrape(),'resources':[]}
stop=threading.Event()
def sample():
    while not stop.wait(2):
        memory={line.split(':')[0]:int(line.split()[1]) for line in Path('/proc/meminfo').read_text().splitlines() if line.startswith(('MemAvailable:','SwapTotal:','SwapFree:'))}
        report['resources'].append({'availableMiB':round(memory['MemAvailable']/1024,1),'swapMiB':round((memory['SwapTotal']-memory['SwapFree'])/1024,1)})
t=threading.Thread(target=sample,daemon=True);t.start()
try:
    for n in range(2):
        # A unique metadata title avoids the standalone endpoint's old proof cache;
        # source, inputs, limits and runtime stay identical between cohorts.
        d=json.loads(json.dumps(fixture));q={'id':0,'problemVersion':1,'title':'Round5 authored fixed validation '+str(uuid.uuid4()),'content':d['contentMarkdown'],'judgeCase':d['judgeCases'],'judgeConfig':d['judgeConfig'],'problemSpec':d['problemSpec']}
        body={'question':q,'referenceSolutions':d['referenceSolutions'],'generationProtocol':d['generation']['protocol'],'oracleSolution':d['generation']['oracleSolution'],'templateId':'prefix_matrix','templateVersion':d['generation']['templateVersion']}
        started=time.monotonic()
        raw=json.load(urllib.request.urlopen(urllib.request.Request('http://'+ip+':8104/api/judge/inner/private-problem/validate',data=json.dumps(body).encode(),headers={'Content-Type':'application/json','X-Internal-Token':env['INTERNAL_AUTH_SECRET']}),timeout=480))
        if 'code' in raw:assert raw.get('code')==0, 'validation service rejected benchmark'
        r=raw.get('data',raw);assert r.get('passed') and len(r.get('combinations',[]))==6 and r.get('oracleValidated') and r.get('propertiesValidated')
        assert not r.get('reusedCombinations'), 'Standalone proof cache invalidates benchmark'
        row={'seconds':round(time.monotonic()-started,3),'passed':True,'combinations':len(r['combinations']),'caseCount':r['caseCount'],'pythonEvidenceReused':r.get('pythonEvidenceReused'),'answerDerivationMillis':r.get('answerDerivationMillis'),'combinationMillis':r.get('combinationMillis')}
        report['samples'].append(row);atomic_json(stage/(label+'.json'),report);print(json.dumps(row),flush=True)
finally:
    stop.set();t.join(4);report['afterMetrics']=scrape()
    report['residualContainers']=len(subprocess.check_output(['docker','ps','-aq','--filter','label=nemo-oj.sandbox=true'],stderr=subprocess.DEVNULL).decode().split())
    report['meanSeconds']=round(sum(x['seconds'] for x in report['samples'])/max(1,len(report['samples'])),3)
    atomic_json(stage/(label+'.json'),report)
