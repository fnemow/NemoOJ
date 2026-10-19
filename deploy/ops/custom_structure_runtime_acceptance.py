"""Compile/run generated SDKs in existing isolated sandbox. Never execute model code on host."""
import json,subprocess,sys,time,urllib.request
from pathlib import Path
sys.path.insert(0,'/usr/local/lib/nemo-ops');from common import atomic_json
stage=Path(sys.argv[1]);programs=json.loads((stage/'programs.json').read_text());report={'passed':False,'programs':[]}
m=json.loads(subprocess.check_output(['docker','inspect','nemo-oj-sandbox-1']))[0];ip=next(iter(m['NetworkSettings']['Networks'].values()))['IPAddress'];env=dict(e.split('=',1) for e in m['Config']['Env'])
for p in programs:
    entry='Runner' if p['language']=='java' and p['mode'].startswith('FUNCTION') else 'Main' if p['language']=='java' else 'runner.py' if p['language']=='python' and p['mode'].startswith('FUNCTION') else 'main.py' if p['language']=='python' else 'main'
    files=[{'fileName':n,'content':v} for n,v in p['files'].items()]
    cases=[{'caseIndex':0,'stdin':p['stdin']}]
    if p['mode'].startswith('FUNCTION'):cases+=[{'caseIndex':1,'stdin':'{"start":null}'}]
    plan={'language':p['language'],'mode':'FUNCTION' if p['mode'].startswith('FUNCTION') else 'ACM','entryPoint':entry,'sourceFiles':files,'cases':cases,'limits':{'timeLimitMs':2000,'memoryLimitKB':262144,'outputLimitBytes':1048576}}
    start=time.monotonic()
    req=urllib.request.Request('http://'+ip+':8090/api/v2/executeCode',data=json.dumps({'submissionId':0,'backgroundValidation':True,'executionPlan':plan}).encode(),headers={'Content-Type':'application/json','auth':env['SANDBOX_SECRET']})
    with urllib.request.urlopen(req,timeout=180) as response:r=json.load(response)
    row={'language':p['language'],'mode':p['mode'],'passed':False}
    if r.get('overallStatus')!=0:
        row['category']='COMPILE_OR_SYSTEM';row['diagnostic']=(r.get('compileMessage') or r.get('systemMessage') or '')[:2200]
    else:
        try:
            actual=r['cases'][0]['stdout']
            if p['mode'].startswith('FUNCTION'):
                parsed=json.loads(actual);assert parsed.get('status')=='OK',parsed.get('error')
                assert parsed['actual']==json.loads(p['expected']);null=json.loads(r['cases'][1]['stdout']);assert null.get('status')=='OK' and null['actual'] is None
            else:assert actual==p['expected'],repr(actual[:300])
            row.update(passed=True,seconds=round(time.monotonic()-start,3),resourcesPresent=all(c.get('time') is not None and c.get('memory') is not None for c in r['cases']))
        except Exception as error:row['category']='RESULT';row['diagnostic']=str(error)[:2200]
    report['programs'].append(row);atomic_json(stage/'runtime.json',report);print(json.dumps(row,ensure_ascii=False),flush=True)
report['passed']=all(r['passed'] for r in report['programs']);atomic_json(stage/'runtime.json',report)
if not report['passed']:raise RuntimeError('Custom structure runtime acceptance failed')
