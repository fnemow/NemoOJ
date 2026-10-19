"""Independent-host load driver. Secrets stay in memory; evidence excludes code/passwords."""
import argparse,concurrent.futures,json,math,subprocess,threading,time,uuid,sys
from pathlib import Path
from cloud_acceptance import Api,CODES,percentile
import cloud_acceptance
from mixed_workload import scenario

def run_completed(data):
    rows=data['submissions']
    return (len(rows)==data['expectedSubmissions'] and len({r.get('id') for r in rows})==len(rows)
            and (bool(data['burst']) or data['dispatchSeconds']>=data['duration'])
            and all(r.get('verdict')==r.get('expected','accepted') for r in rows)
            and not data.get('abort') and not data.get('monitorError')
            and not data['summary']['readErrors'])

def final_drained(final,expected,allow_errors=False):
    attempts=[r.split('\t') for r in final['attempts'].splitlines() if r.strip()]
    queues=[r.split('\t') for r in final['queue'].splitlines() if r.strip() and not r.startswith('name\t')]
    return (final['pending']==0 and final['fixturesRemoved'] and final['residual']==0
            and len(attempts)==expected and all(len(r)==3 and r[1]=='1' and r[2] in (['2','3'] if allow_errors else ['2']) for r in attempts)
            and bool(queues) and all(r[-2:]==['0','0'] for r in queues))

def main(args):
    cloud_acceptance.BASE=args.base+'/api'
    def remote(code):
        command='sudo python3 -c "exec(bytes.fromhex(\\"'+code.encode().hex()+'\\").decode())"'
        run=subprocess.run(['ssh','-i',args.key,'-o','BatchMode=yes','-o','ConnectTimeout=5',args.host,command],capture_output=True,timeout=25)
        if run.returncode:
            diagnostic=run.stderr.decode('utf-8','replace').strip().splitlines()
            raise RuntimeError('Remote control failed (exit '+str(run.returncode)+'): '+(diagnostic[-1][:200] if diagnostic else 'No SSH diagnostic'))
        return json.loads(run.stdout)
    prefix="import sys,json,subprocess,pathlib,time;sys.path.insert(0,'/usr/local/lib/nemo-ops');from common import mysql,container\n"
    fixture=remote(prefix+'''import crypt,secrets
rows=[]
for _ in range(8):
 account='schedule_'+secrets.token_hex(6);password=secrets.token_hex(16);hashed=crypt.crypt(password,crypt.mksalt(crypt.METHOD_BLOWFISH))
 uid=int(mysql("INSERT INTO user(userAccount,userPassword,userName,userRole) VALUES ('"+account+"','"+hashed+"','Scheduling acceptance','user'); SELECT LAST_INSERT_ID();"))
 rows.append({'id':uid,'account':account,'password':password})
print(json.dumps(rows))
''')
    ids=','.join(str(r['id']) for r in fixture);stop=threading.Event();lock=threading.Lock();clients=[];client_locks=[threading.Lock() for _ in fixture]
    data={'duration':args.duration,'interval':args.interval,'burst':args.burst,
          'fixtureIds':[r['id'] for r in fixture], 'profile':args.profile,
          'driver':'independent Windows host over private SSH tunnel','submissions':[],'resources':[],'probeFailures':[],'started':time.time()}
    def save():
        path=Path(args.output);path.parent.mkdir(parents=True,exist_ok=True);temporary=path.with_suffix('.tmp');temporary.write_text(json.dumps(data),encoding='utf-8');temporary.replace(path)
    save()
    def sample():
        body='''mem={l.split(':')[0]:int(l.split()[1]) for l in pathlib.Path('/proc/meminfo').read_text().splitlines()}
vm={l.split()[0]:int(l.split()[1]) for l in pathlib.Path('/proc/vmstat').read_text().splitlines()}
print(json.dumps({'at':time.time(),'availableMiB':mem['MemAvailable']/1024,'swapUsedMiB':(mem['SwapTotal']-mem['SwapFree'])/1024,'swapIn':vm['pswpin'],'swapOut':vm['pswpout'],'oom':vm.get('oom_kill',0),'cpuTicks':list(map(int,pathlib.Path('/proc/stat').read_text().splitlines()[0].split()[1:])),'pressure':{k:pathlib.Path('/proc/pressure/'+k).read_text() for k in ['cpu','memory','io']},'pending':int(mysql('SELECT COUNT(*) FROM question_submit WHERE status IN(0,1) AND isDelete=0;'))}),flush=True)
'''
        # Stream all probes over one SSH connection instead of opening a new
        # connection every ten seconds. Keepalive bounds a stalled connection.
        script=prefix+'end=time.monotonic()+'+str(args.duration+180)+'\nwhile time.monotonic()<end:\n'+''.join('    '+line+'\n' for line in body.splitlines())+'    time.sleep(10)\n'
        command='sudo python3 -u -c "exec(bytes.fromhex(\\"'+script.encode().hex()+'\\").decode())"'
        consecutive_failures=0
        while not stop.is_set():
            process=None
            try:
                process=subprocess.Popen(['ssh','-i',args.key,'-o','BatchMode=yes','-o','ConnectTimeout=5','-o','ServerAliveInterval=5','-o','ServerAliveCountMax=3',args.host,command],stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
                while not stop.is_set():
                    line=process.stdout.readline()
                    if not line:raise RuntimeError('Resource stream disconnected')
                    row=json.loads(line);consecutive_failures=0
                    with lock:data['resources'].append(row);save()
                    if row['availableMiB']<350 or row['pending']>12:
                        with lock:data['abort']='Resource/queue safety bound';save()
                        stop.set();break
            except Exception as e:
                consecutive_failures+=1
                with lock:
                    data['probeFailures'].append({'at':time.time(),'error':str(e),'consecutive':consecutive_failures});save()
                    if consecutive_failures>=3:data['monitorError']='Three consecutive resource probe failures';stop.set()
            finally:
                if process is not None:
                    process.terminate()
                    try:process.communicate(timeout=5)
                    except subprocess.TimeoutExpired:process.kill();process.communicate()
            if not stop.is_set():stop.wait(2)
    def submit(n):
        index=n%len(clients)
        number,mode,expected,lang,code=scenario(n) if args.profile=='mixed' else (3,'FUNCTION','accepted',list(CODES)[n%3],CODES[list(CODES)[n%3]])
        row={'sequence':n,'language':lang,'problemNo':number,'mode':mode,'expected':expected};start=time.monotonic()
        try:
            with client_locks[index]:
                api=clients[index]
                problem=problems[number]
                sid=str(api.call('/question/question_submit/do',{'questionId':problem['id'],'problemVersion':problem['problemVersion'],'language':lang,'executionMode':mode,'code':code,'clientRequestId':str(uuid.uuid4())}))
                row['id']=sid;row['ackSeconds']=time.monotonic()-start
                end=time.monotonic()+180
                while time.monotonic()<end:
                    detail=api.call('/question/question_submit/get/vo?id='+sid)
                    if detail['status'] in [2,3]:row.update(seconds=time.monotonic()-start,verdict=detail.get('verdict'));break
                    time.sleep(.5)
                else:row['error']='Terminal timeout'
            if row.get('verdict')!=expected:stop.set()
        except Exception as e:row['error']=str(e)[:160];stop.set()
        with lock:data['submissions'].append(row);save()
    def reads():
        api=Api();lat=[];errors=0
        while not stop.is_set():
            start=time.monotonic()
            try:api.call('/question/list/page/vo',{'current':1,'pageSize':20});lat.append(time.monotonic()-start)
            except Exception:errors+=1
            stop.wait(.25)
        return lat,errors
    try:
        for row in fixture:
            api=Api();api.login(row['account'],row['password']);clients.append(api)
        data['loginBackoffSeconds']=sum(api.login_wait_seconds for api in clients)
        numbers=[3,4,13,16,19,21] if args.profile=='mixed' else [3]
        problems={n:clients[0].call('/question/get/vo/by-number?problemNo='+str(n)) for n in numbers}
        with concurrent.futures.ThreadPoolExecutor(max_workers=13) as pool:
            monitoring=pool.submit(sample);readers=[pool.submit(reads) for _ in range(4)];futures=[];dispatch_start=time.monotonic();end=dispatch_start+args.duration;n=0
            while (n<args.burst if args.burst else time.monotonic()<end) and not stop.is_set():
                futures.append(pool.submit(submit,n));n+=1;stop.wait(0 if args.burst else args.interval)
            data['dispatchSeconds']=time.monotonic()-dispatch_start
            data['expectedSubmissions']=args.burst or math.ceil(args.duration/args.interval)
            for f in futures:f.result()
            stop.set();batches=[r.result() for r in readers];monitoring.result()
        lat=[x for a,_ in batches for x in a];times=[r['seconds'] for r in data['submissions'] if 'seconds' in r]
        data['summary']={'submitted':len(data['submissions']),'accepted':sum(r.get('verdict')=='accepted' for r in data['submissions']),'expectedVerdicts':sum(r.get('verdict')==r.get('expected','accepted') for r in data['submissions']),'p50':percentile(times,.5),'p95':percentile(times,.95),'max':max(times) if times else None,'reads':len(lat),'readErrors':sum(e for _,e in batches),'readP95':percentile(lat,.95)}
        data['summary']['passed']=run_completed(data)
    finally:
        stop.set()
        save()
        final_script=prefix+'''ids="'''+ids+'''"
pending=int(mysql('SELECT COUNT(*) FROM question_submit WHERE userId IN ('+ids+') AND status IN(0,1) AND isDelete=0;'))
rows=mysql('SELECT id,attemptCount,status FROM question_submit WHERE userId IN ('+ids+');')
if pending==0:mysql("UPDATE question_submit SET isDelete=1 WHERE userId IN ("+ids+"); UPDATE user SET isDelete=1,userRole='ban' WHERE id IN ("+ids+");")
queue=subprocess.check_output(['docker','exec',container('core','rabbitmq'),'rabbitmqctl','-q','list_queues','name','messages_ready','messages_unacknowledged']).decode()
residual=lambda:len(subprocess.check_output(['docker','ps','-aq','--filter','label=nemo-oj.sandbox=true']).splitlines())
initial_residual=residual();deadline=time.monotonic()+15
while pending==0 and residual()>0 and time.monotonic()<deadline:time.sleep(.5)
print(json.dumps({'pending':pending,'fixturesRemoved':pending==0,'attempts':rows,'queue':queue,'initialResidual':initial_residual,'residual':residual()}))
'''
        for attempt in range(3):
            try:data['final']=remote(final_script);break
            except Exception:
                if attempt==2:
                    data['finalError']='Final control unavailable after three attempts'
                    if 'summary' in data:data['summary']['passed']=False
                    save();raise
                time.sleep(attempt+1)
        data['ended']=time.time()
        drained=final_drained(data['final'],len(data['submissions']),args.profile=='mixed')
        if 'summary' in data:data['summary']['drained']=drained;data['summary']['passed']=data['summary']['passed'] and drained
        save()
    print(json.dumps(data.get('summary',{})))

if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--base',default='http://127.0.0.1:18082');p.add_argument('--host',default='ubuntu@43.142.118.220');p.add_argument('--key',required=True);p.add_argument('--burst',type=int,default=0);p.add_argument('--duration',type=int,default=900);p.add_argument('--interval',type=float,default=6);p.add_argument('--profile',choices=['binary','mixed'],default='binary');p.add_argument('--output',required=True);main(p.parse_args())
