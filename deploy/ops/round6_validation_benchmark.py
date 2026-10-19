"""Matched full-validation benchmark; no user source, keys or model calls in reports."""
import json
import subprocess
import sys
import time
import urllib.request
import uuid
from pathlib import Path
sys.path.insert(0, '/usr/local/lib/nemo-ops')
from common import mysql, atomic_json

stage=Path(sys.argv[1]);label=sys.argv[2];assert label in ('before','cold','warm')
fixture=json.loads(Path('/home/ubuntu/nemo-generation-20261011/fixtures.json').read_text())['prefix_matrix']
def meta(service):return json.loads(subprocess.check_output(['docker','inspect','nemo-oj-'+service+'-1']))[0]
def metrics():
    m=meta('sandbox');env=dict(x.split('=',1) for x in m['Config']['Env']);ip=next(iter(m['NetworkSettings']['Networks'].values()))['IPAddress']
    source=urllib.request.urlopen('http://'+ip+':'+env.get('MANAGEMENT_PORT','9190')+'/actuator/prometheus',timeout=5).read().decode()
    return '\n'.join(line for line in source.splitlines() if line.startswith(('nemo_sandbox_stage_duration_seconds_count','nemo_sandbox_stage_duration_seconds_sum','nemo_sandbox_trusted_compile_cache_total','nemo_sandbox_container_cleanup_failures_total')))
assert mysql("SELECT COUNT(*) FROM ai_task WHERE status IN ('QUEUED','RUNNING')")=='0'
assert mysql('SELECT COUNT(*) FROM question_submit WHERE isDelete=0 AND status IN (0,1)')=='0'
m=meta('judge-service');ip=next(iter(m['NetworkSettings']['Networks'].values()))['IPAddress'];env=dict(x.split('=',1) for x in m['Config']['Env'])
r={'label':label,'samples':[],'beforeMetrics':metrics(),'modelCalls':0}
for i in range(1 if label=='cold' else 2):
    d=json.loads(json.dumps(fixture))
    q={'id':0,'problemVersion':1,'title':'Round6 authored fixed '+str(uuid.uuid4()),'content':d['contentMarkdown'],
       'judgeCase':d['judgeCases'],'judgeConfig':d['judgeConfig'],'problemSpec':d['problemSpec']}
    body={'question':q,'referenceSolutions':d['referenceSolutions'],'generationProtocol':d['generation']['protocol'],
          'oracleSolution':d['generation']['oracleSolution'],'templateId':'prefix_matrix','templateVersion':d['generation']['templateVersion']}
    start=time.monotonic()
    raw=json.load(urllib.request.urlopen(urllib.request.Request('http://'+ip+':8104/api/judge/inner/private-problem/validate',data=json.dumps(body).encode(),headers={'Content-Type':'application/json','X-Internal-Token':env['INTERNAL_AUTH_SECRET']}),timeout=480))
    if 'code' in raw:assert raw['code']==0,'Benchmark API rejected'
    value=raw.get('data',raw)
    assert value.get('passed') and value.get('oracleValidated') and value.get('propertiesValidated') and len(value.get('combinations',[]))==6
    assert not value.get('reusedCombinations'),'Standalone proof cache would invalidate comparison'
    row={'seconds':round(time.monotonic()-start,3),'passed':True,'caseCount':value['caseCount'],'combinations':6,
         'answerDerivationMillis':value.get('answerDerivationMillis'),'combinationMillis':value.get('combinationMillis')}
    r['samples'].append(row);atomic_json(stage/(label+'.json'),r);print(json.dumps(row),flush=True)
r['afterMetrics']=metrics();r['meanSeconds']=round(sum(x['seconds'] for x in r['samples'])/len(r['samples']),3)
r['residualContainers']=len(subprocess.check_output(['docker','ps','-aq','--filter','label=nemo-oj.sandbox=true']).split())
assert r['residualContainers']==0
r['passed']=True;atomic_json(stage/(label+'.json'),r)
