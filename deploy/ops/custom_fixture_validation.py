"""Full 20-case graph validation with independent oracle, no paid model calls."""
import copy,json,subprocess,sys,time,urllib.request,urllib.error
from pathlib import Path
sys.path.insert(0,'/usr/local/lib/nemo-ops');from common import atomic_json
stage=Path(sys.argv[1]);body=json.loads((stage/'fixture-request.json').read_text())
meta=json.loads(subprocess.check_output(['docker','inspect','nemo-oj-judge-service-1']))[0]
ip=next(iter(meta['NetworkSettings']['Networks'].values()))['IPAddress'];env=dict(e.split('=',1) for e in meta['Config']['Env'])
def call(value):
    r=urllib.request.Request('http://'+ip+':8104/api/judge/inner/private-problem/validate',data=json.dumps(value).encode(),headers={'Content-Type':'application/json','X-Internal-Token':env['INTERNAL_AUTH_SECRET']})
    with urllib.request.urlopen(r,timeout=480) as response:return json.load(response)
start=time.monotonic();value=call(body);v=value.get('data',value)
report={'passed':v.get('passed',False),'category':v.get('category'),'combination':v.get('combination'),'seconds':round(time.monotonic()-start,3),'caseCount':v.get('caseCount'),'combinations':len(v.get('combinations',[])),'oracleValidated':v.get('oracleValidated'),'runtimeValidated':v.get('runtimeValidated'),'completeProofReused':v.get('completeProofReused',False)}
atomic_json(stage/'fixture-validation.json',report);print(json.dumps(report),flush=True)
assert report['passed'] and report['combinations']==6 and report['oracleValidated'] and report['runtimeValidated']
bad=copy.deepcopy(body);raw=json.loads(bad['question']['problemSpec']['judgeCases'][1]['input']);raw['start']['nodes'][0]['left']=999;bad['question']['problemSpec']['judgeCases'][1]['input']=json.dumps(raw)
try:r=call(bad);rejected=r.get('code',0)!=0 or not r.get('data',r).get('passed',False)
except urllib.error.HTTPError:rejected=True
assert rejected;report['invalidReferenceRejected']=True;atomic_json(stage/'fixture-validation.json',report)
print(json.dumps(report),flush=True)
