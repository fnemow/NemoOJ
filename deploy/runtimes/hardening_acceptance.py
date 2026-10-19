#!/usr/bin/env python3
"""Bounded security/concurrency checks for the isolated acceptance sandbox."""
import concurrent.futures, json, os, threading, urllib.request, urllib.error
BASE=os.environ['SANDBOX_BASE_URL'].rstrip('/')
SECRET=os.environ['SANDBOX_SECRET']
def request(path, body, secret=SECRET):
    req=urllib.request.Request(BASE+'/api'+path,data=json.dumps(body).encode(),
        headers={'auth':secret,'Content-Type':'application/json'})
    try:
        with urllib.request.urlopen(req,timeout=90) as r: return r.status,json.load(r)
    except urllib.error.HTTPError as e: return e.code,e.read().decode()
def plan(code, seconds=2):
    return {'executionPlan':{'language':'python','mode':'ACM','entryPoint':'main.py',
        'sourceFiles':[{'fileName':'main.py','content':code}], 'cases':[{'caseIndex':0,'stdin':''}],
        'limits':{'timeLimitMs':seconds*1000,'memoryLimitKB':262144,'outputLimitBytes':8192}}}
def execute(code,seconds=2):
    status,body=request('/v2/executeCode',plan(code,seconds))
    assert status==200 and body['overallStatus']==0,(status,body)
    return body['cases'][0]['stdout'].strip()

assert request('/v2/executeCode',plan('print(1)'),secret='invalid')[0]==403
print('authentication PASS',flush=True)
probe='''import os,socket,pathlib,json
assert os.getuid()==65534 and os.getgid()==65534
for path in ['/root-write-test','/app/write-test']:
 try:
  open(path,'w').close()
  raise AssertionError('writable protected path')
 except OSError: pass
assert set(os.listdir('/sys/class/net'))=={'lo'}
try:
 socket.create_connection(('192.0.2.1',80),timeout=.2)
 raise AssertionError('network reachable')
except OSError: pass
for field in ('NoNewPrivs:\\t1','CapEff:\\t0000000000000000'):
 assert field in pathlib.Path('/proc/self/status').read_text()
assert pathlib.Path('/sys/fs/cgroup/pids.max').read_text().strip()=='64'
assert pathlib.Path('/sys/fs/cgroup/memory.max').read_text().strip()=='268435456'
quota,period=map(int,pathlib.Path('/sys/fs/cgroup/cpu.max').read_text().split())
assert quota==period
print('isolated')
'''
assert execute(probe)=='isolated'
print('uid/rootfs/workspace/network/capabilities/cgroup limits PASS',flush=True)
fork='''import os,time
children=[]
limited=False
try:
 for i in range(90):
  try: pid=os.fork()
  except OSError:
   limited=True
   break
  if pid==0:
   time.sleep(1)
   os._exit(0)
  children.append(pid)
finally:
 for pid in children: os.waitpid(pid,0)
assert limited and len(children)<64
print('limited')
'''
assert execute(fork,5)=='limited'
print('bounded process exhaustion PASS',flush=True)
status,result=request('/executeCode',{'language':'java','code':'public class Main {public static void main(String[] a){System.out.print(Integer.parseInt(a[0])+Integer.parseInt(a[1]));}}','inputList':['1 2']})
assert status==200 and result['status']==0 and result['outputList']==['3'],(status,result)
assert request('/executeCode',{'language':'python','code':'print(1)','inputList':['']})[0]==400
print('legacy Java V1 and non-Java rejection PASS',flush=True)
stale=plan('print(1)');stale['executionPlan']['runtimeId']='stale-runtime'
status,result=request('/v2/executeCode',stale)
assert status==200 and result['overallStatus']==6,(status,result)
print('stale runtime rejection PASS',flush=True)
barrier=threading.Barrier(8)
def load(_):
    barrier.wait()
    return request('/v2/executeCode',plan('import time\ntime.sleep(5)\nprint(1)',4))
with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool: results=list(pool.map(load,range(8)))
codes=[status for status,_ in results]
assert 503 in codes and 200 in codes,codes
assert all(status==503 or (status==200 and body['overallStatus']==0) for status,body in results),results
assert execute('print(1)')=='1'
print('8 concurrent requests / overload / recovery PASS',codes,flush=True)
