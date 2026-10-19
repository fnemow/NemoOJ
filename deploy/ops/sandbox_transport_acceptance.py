"""Real sandbox regression and concurrent isolation, no users/model/database writes."""
import concurrent.futures
import json
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

def main(output):
    m=json.loads(subprocess.check_output(['docker','inspect','nemo-oj-sandbox-1']))[0]
    ip=next(iter(m['NetworkSettings']['Networks'].values()))['IPAddress'];env=dict(x.split('=',1) for x in m['Config']['Env'])
    base='http://'+ip+':8090/api';headers={'Content-Type':'application/json','auth':env['SANDBOX_SECRET']}
    def call(path,body=None):
        request=urllib.request.Request(base+path,data=None if body is None else json.dumps(body).encode(),headers=headers)
        with urllib.request.urlopen(request,timeout=80) as response:return json.load(response)
    # Actual port comes from deployment; never expose its environment map.
    base='http://'+ip+':'+env.get('SERVER_PORT','8090')+'/api'
    capabilities=call('/v2/capabilities');runtimes={r['language']:r['runtimeId'] for r in capabilities if r.get('imageReady')}
    report={'cases':[],'parallelIsolation':False}
    def execute(language,source,stdin='',limit=500):
        name={'java':'Main.java','python':'main.py','cpp':'main.cpp'}[language]
        plan={'runtimeId':runtimes[language],'language':language,'mode':'ACM','entryPoint':{'java':'Main','cpp':'main','python':'main.py'}[language],'sourceFiles':[{'fileName':name,'content':source}],'cases':[{'caseIndex':0,'stdin':stdin}],'limits':{'timeLimitMs':limit,'memoryLimitKB':262144,'outputLimitBytes':4096}}
        return call('/v2/executeCode',{'submissionId':1,'executionPlan':plan})
    samples=[('java','AC','public class Main{public static void main(String[]a){System.out.println(42);}}',0),
             ('cpp','AC','#include <iostream>\nint main(){std::cout<<42;}',0),
             ('python','AC','print(42)',0),('python','CE','if :',1),
             ('python','RE','raise RuntimeError("synthetic regression")',2),
             ('python','TLE','while True: pass',3),
             ('python','MLE','a=bytearray(512*1024*1024)\nprint(len(a))',4),
             ('python','OLE','while True: print("X"*8192,flush=True)',5)]
    for language,name,source,expected in samples:
        start=time.monotonic();r=execute(language,source)
        cases=r.get('cases') or [];status=int(cases[0]['status']) if cases else (1 if r.get('compileMessage') else -1)
        row={'language':language,'case':name,'status':status,'seconds':round(time.monotonic()-start,3),'hasDiagnostic':bool(r.get('compileMessage') or any(c.get('stderr') for c in cases))}
        if cases:row.update(time=cases[0].get('time'),memory=cases[0].get('memory'))
        report['cases'].append(row);Path(output).write_text(json.dumps(report,indent=2));print(json.dumps(row),flush=True)
        assert status==expected,(language,name,status)
        if name=='AC':assert int(cases[0]['memory'])>0 and int(cases[0]['time'])>=0
        if name in ('CE','RE'):assert row['hasDiagnostic']
    code='from pathlib import Path\nvalue=input()\nPath("/tmp/value").write_text(value)\nprint(Path("/tmp/value").read_text())'
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        futures=[pool.submit(execute,'python',code,v+'\n') for v in ('ALPHA','BETA')]
        for value,future in zip(('ALPHA','BETA'),futures):
            cases=future.result()['cases'];assert int(cases[0]['status'])==0 and cases[0]['stdout'].strip()==value
    report['parallelIsolation']=True
    report['residualContainers']=len(subprocess.check_output(['docker','ps','-aq','--filter','label=nemo-oj.sandbox=true']).splitlines())
    report['passed']=report['residualContainers']==0;assert report['passed']
    Path(output).write_text(json.dumps(report,indent=2));print(json.dumps({'passed':True,'parallelIsolation':True,'residualContainers':0}))

if __name__=='__main__':main(sys.argv[1])
