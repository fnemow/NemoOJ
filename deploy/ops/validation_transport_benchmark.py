"""Same authored candidate before/after; zero model calls; no source in evidence."""
import json
import subprocess
import sys
import time
import urllib.request
from pathlib import Path
sys.path.insert(0,'/usr/local/lib/nemo-ops')
from common import mysql

def meta(service):
    return json.loads(subprocess.check_output(['docker','inspect','nemo-oj-'+service+'-1']))[0]

def main(output):
    assert mysql("SELECT COUNT(*) FROM question_submit WHERE status IN (0,1) AND isDelete=0")=='0'
    assert mysql("SELECT COUNT(*) FROM ai_task WHERE status IN ('QUEUED','RUNNING')")=='0'
    d=json.loads(Path('/home/ubuntu/nemo-generation-20261011/fixtures.json').read_text())['prefix_matrix']
    q={'id':0,'problemVersion':1,'title':'authored transport benchmark','content':d['contentMarkdown'],'judgeCase':d['judgeCases'],'judgeConfig':d['judgeConfig'],'problemSpec':d['problemSpec']}
    req={'question':q,'referenceSolutions':d['referenceSolutions'],'generationProtocol':d['generation']['protocol'],'oracleSolution':d['generation']['oracleSolution'],'templateId':'prefix_matrix','templateVersion':'trusted-v1'}
    m=meta('judge-service');ip=next(iter(m['NetworkSettings']['Networks'].values()))['IPAddress'];env=dict(x.split('=',1) for x in m['Config']['Env'])
    rows=[]
    for i in range(2):
        start=time.monotonic()
        with urllib.request.urlopen(urllib.request.Request('http://'+ip+':8104/api/judge/inner/private-problem/validate',data=json.dumps(req).encode(),headers={'Content-Type':'application/json','X-Internal-Token':env['INTERNAL_AUTH_SECRET']}),timeout=400) as response:r=json.load(response)
        if 'data' in r:r=r['data']
        row={k:r.get(k) for k in ('passed','category','combinationMillis','answerDerivationMillis','pythonEvidenceReused','propertiesValidated')}
        row.update(seconds=round(time.monotonic()-start,3),combinations=len(r.get('combinations',[])))
        rows.append(row);Path(output).write_text(json.dumps(rows,indent=2));print(json.dumps(row),flush=True)
        assert row['passed'] and row['combinations']==6 and row['propertiesValidated']

if __name__=='__main__':main(sys.argv[1])
