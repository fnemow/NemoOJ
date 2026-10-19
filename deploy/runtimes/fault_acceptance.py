#!/usr/bin/env python3
"""Create disposable fault-test sandbox containers on the acceptance VM only."""
import json, pathlib, subprocess, time, urllib.request, urllib.error
root=pathlib.Path.cwd()
assert root.name=='nemooj-acceptance-20260918','Run in the isolated acceptance directory'
env=dict(line.split('=',1) for line in (root/'sandbox.env').read_text().splitlines())
secret=env['SANDBOX_SECRET']
image='eclipse-temurin:17-jre-jammy'
jar=str(root/'nemo-sandbox/target/nemo-sandbox-0.0.1-SNAPSHOT.jar')
def docker(*args): return subprocess.check_output(['docker',*args],text=True).strip()
def start(name,overrides):
    work=root/name;work.mkdir(exist_ok=True)
    args=['run','-d','--name',name,'--memory','512m','--env-file',str(root/'sandbox.env'),
          '-p','127.0.0.1:18190:8090','-v','/var/run/docker.sock:/var/run/docker.sock',
          '-v',f'{root}:{root}','-w',str(work)]
    for key,value in overrides.items():args.extend(['-e',key+'='+value])
    docker(*args,image,'java','-Xmx320m','-jar',jar)
def request(path,body=None):
    req=urllib.request.Request('http://127.0.0.1:18190/api'+path,
        data=None if body is None else json.dumps(body).encode(),
        headers={'auth':secret,'Content-Type':'application/json'})
    with urllib.request.urlopen(req,timeout=60) as r:return json.load(r)
def remove(name): subprocess.run(['docker','rm','-f',name],check=True,stdout=subprocess.DEVNULL)
name='nemooj-acceptance-disabled'
try:
    start(name,{'JUDGE_ENABLED_LANGUAGES':'java'})
    for i in range(30):
        try: caps=request('/v2/capabilities');break
        except (OSError,urllib.error.URLError):time.sleep(1)
    else:raise AssertionError('disabled-language fixture did not start')
    assert len(caps)==1 and caps[0]['language']=='java' and caps[0]['imageReady'],caps
    body={'executionPlan':{'language':'python','mode':'ACM','entryPoint':'main.py',
       'sourceFiles':[{'fileName':'main.py','content':'print(1)'}],
       'cases':[{'caseIndex':0,'stdin':''}],
       'limits':{'timeLimitMs':1000,'memoryLimitKB':262144,'outputLimitBytes':4096}}}
    assert request('/v2/executeCode',body)['overallStatus']==6
    print('disabled language capability and execution rejection PASS',flush=True)
finally:remove(name)
name='nemooj-acceptance-missing-image'
try:
    start(name,{'JUDGE_ENABLED_LANGUAGES':'python','SANDBOX_PYTHON_IMAGE':'nemooj-nonexistent-acceptance:missing'})
    for i in range(30):
        state=json.loads(docker('inspect',name))[0]['State']
        if not state['Running']:break
        time.sleep(1)
    assert not state['Running'] and state['ExitCode']!=0,state
    logs=docker('logs',name)
    assert 'nemooj-nonexistent-acceptance:missing' in logs
    print('missing image startup fails closed PASS',flush=True)
finally:remove(name)
