"""Fresh-process isolation and fail-fast acceptance. Only authored test source enters sandbox."""
import concurrent.futures,json,subprocess,sys,time,urllib.request
from pathlib import Path
m=json.loads(subprocess.check_output(['docker','inspect','nemo-oj-sandbox-1']))[0]
env=dict(e.split('=',1) for e in m['Config']['Env']);ip=next(iter(m['NetworkSettings']['Networks'].values()))['IPAddress']
base='http://'+ip+':'+env.get('SERVER_PORT','8090')+'/api'
def call(path,body=None):
    request=urllib.request.Request(base+path,data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json','auth':env['SANDBOX_SECRET']})
    return json.load(urllib.request.urlopen(request,timeout=100))
runtime=next(r['runtimeId'] for r in call('/v2/capabilities') if r['language']=='python' and r['imageReady'])
def execute(source,inputs,background=True,limit=200):
    plan={'runtimeId':runtime,'language':'python','mode':'ACM','entryPoint':'main.py','sourceFiles':[{'fileName':'main.py','content':source}],
        'cases':[{'caseIndex':i,'stdin':s} for i,s in enumerate(inputs)],'limits':{'timeLimitMs':limit,'memoryLimitKB':262144,'outputLimitBytes':4096}}
    return call('/v2/executeCode',{'submissionId':0 if background else 1,'backgroundValidation':background,'executionPlan':plan})
report={'cases':[]}
for name,source,status in [('RE','raise RuntimeError("authored")',2),('TLE','while True:pass',3),('MLE','a=bytearray(512*1024*1024)',4),('OLE','while True:print("X"*8192,flush=True)',5)]:
    started=time.monotonic();r=execute(source,['x']*20)
    assert r['overallStatus']==status and len(r['cases'])==1
    c=r['cases'][0];assert c['memory']>0 and c['stderr']
    report['cases'].append({'name':name,'status':status,'executedCases':len(r['cases']),'seconds':round(time.monotonic()-started,3),'resourcesPresent':True})
source='import pathlib\np=pathlib.Path("/tmp/round5-state")\nassert not p.exists()\nassert not pathlib.Path("/host-cgroup").exists()\ns=input()\np.write_text(s)\nprint(s)'
inputs=[str(i) for i in range(12)];r=execute(source,inputs,limit=500)
assert r['overallStatus']==0 and [c['stdout'].strip() for c in r['cases']]==inputs and all(c['memory']>0 for c in r['cases'])
report['perCaseIsolation']=True
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
    bg=pool.submit(execute,source,[str(i) for i in range(12)],True,500)
    time.sleep(.4);formal=pool.submit(execute,source,['formal-only'],False,500)
    a,b=bg.result(),formal.result()
assert a['overallStatus']==b['overallStatus']==0 and b['cases'][0]['stdout'].strip()=='formal-only'
report['formalBackgroundIsolation']=True
assert len(subprocess.check_output(['docker','ps','-aq','--filter','label=nemo-oj.sandbox=true']).decode().split())==0
report['residualContainers']=0;report['passed']=True
Path(sys.argv[1]).write_text(json.dumps(report,indent=2));print(json.dumps(report),flush=True)
