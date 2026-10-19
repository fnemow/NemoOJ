"""Exercise authored IO fixtures in the existing sandbox, without user submissions."""
import json,subprocess,sys,time,urllib.request
from pathlib import Path
stage=Path(sys.argv[1]);programs=json.loads((stage/'programs.json').read_text())
m=json.loads(subprocess.check_output(['docker','inspect','nemo-oj-sandbox-1']))[0]
ip=next(iter(m['NetworkSettings']['Networks'].values()))['IPAddress']
env=dict(item.split('=',1) for item in m['Config']['Env'])
headers={'Content-Type':'application/json','auth':env['SANDBOX_SECRET']}
report={'passed':False,'programs':[]}
for p in programs:
    plan={'language':p['language'],'mode':'ACM','entryPoint':'Main' if p['language']=='java' else 'main.py' if p['language']=='python' else 'main',
          'sourceFiles':[{'fileName':p['fileName'],'content':p['source']}],
          'cases':[{'caseIndex':i,'stdin':case['input']} for i,case in enumerate(p['cases'])],
          'limits':{'timeLimitMs':3000,'memoryLimitKB':262144,'outputLimitBytes':4096}}
    started=time.monotonic()
    req=urllib.request.Request('http://'+ip+':8090/api/v2/executeCode',data=json.dumps({'submissionId':0,'backgroundValidation':True,'executionPlan':plan}).encode(),headers=headers)
    with urllib.request.urlopen(req,timeout=180) as response:value=json.load(response)
    assert value.get('overallStatus')==0,'Template fixture failed: '+p['language']+'/'+p['kind']
    assert len(value['cases'])==len(p['cases'])
    assert all(actual['stdout']==expected['output'] for actual,expected in zip(value['cases'],p['cases'])),'IO output mismatch'
    row={'language':p['language'],'kind':p['kind'],'passed':True,'cases':len(p['cases']),'seconds':round(time.monotonic()-started,3)}
    report['programs'].append(row)
    (stage/'runtime-verification.json').write_text(json.dumps(report,indent=2))
    print(json.dumps(row),flush=True)
report['passed']=True
(stage/'runtime-verification.json').write_text(json.dumps(report,indent=2))
print(json.dumps({'passed':True,'programs':len(programs),'cases':sum(len(p['cases']) for p in programs)}),flush=True)
