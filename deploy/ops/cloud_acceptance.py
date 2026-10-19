"""Opt-in cloud acceptance; never opens registration or creates login HTML pages.

Runs on the core host's private API-only loopback listener. Accounts are created
directly with BCrypt, held in memory, and soft-deleted after all submissions end.
Evidence contains timings and outcomes, never source code or account passwords.
"""
import argparse
import concurrent.futures
import http.cookiejar
import gzip
import json
import math
import re
import secrets
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path
from common import mysql, container, atomic_json

BASE='http://127.0.0.1:18081/api'
CODES={
 'java':'class Solution { public int search(int[] nums,int target){int l=0,r=nums.length-1;while(l<=r){int m=l+(r-l)/2;if(nums[m]==target)return m;if(nums[m]<target)l=m+1;else r=m-1;}return -1;} }',
 'python':'class Solution:\n    def search(self, nums, target):\n        l,r=0,len(nums)-1\n        while l<=r:\n            m=(l+r)//2\n            if nums[m]==target: return m\n            if nums[m]<target: l=m+1\n            else: r=m-1\n        return -1\n',
 'cpp':'#include <vector>\nusing namespace std; class Solution {public: int search(vector<int>& nums,int target){int l=0,r=(int)nums.size()-1;while(l<=r){int m=l+(r-l)/2;if(nums[m]==target)return m;if(nums[m]<target)l=m+1;else r=m-1;}return -1;}};'}
ACM={'java':'public class Main { public static void main(String[] args){System.out.println("42");}}',
     'python':'print(42)\n','cpp':'#include <iostream>\nint main(){std::cout<<42<<std::endl;}'}

class ApiFailure(RuntimeError):
    def __init__(self,result,status,retry_after=None):
        super().__init__(str(result.get('code'))+': '+str(result.get('message')))
        self.status=status;self.error_key=result.get('errorKey');self.retry_after=retry_after

class Api:
    def __init__(self):
        self.opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()));self.csrf=None
    def call(self,path,body=None,timeout=60):
        headers={'Accept':'application/json','Accept-Encoding':'gzip'};data=None
        if body is not None:
            if not self.csrf:self.csrf=self.call('/user/csrf')
            headers[self.csrf['headerName']]=self.csrf['token'];headers['Content-Type']='application/json';data=json.dumps(body).encode()
        request=urllib.request.Request(BASE+path,data=data,headers=headers)
        status=200;retry_after=None
        try:
            with self.opener.open(request,timeout=timeout) as response:
                raw=response.read();result=json.loads(gzip.decompress(raw) if response.headers.get('Content-Encoding','').lower()=='gzip' else raw)
        except urllib.error.HTTPError as error:
            status=error.code;retry_after=error.headers.get('Retry-After')
            try:result=json.loads(error.read())
            except Exception:raise RuntimeError('HTTP '+str(error.code))
        if result.get('code')!=0:raise ApiFailure(result,status,retry_after)
        return result.get('data')
    def login(self,account,password):
        self.login_wait_seconds=0
        for attempt in range(3):
            try:
                self.call('/user/login',{'userAccount':account,'userPassword':password});self.csrf=None;return
            except RuntimeError as error:
                message=str(error)
                # Test setup obeys the real login sliding window. Do not retry
                # invalid credentials or disable security limits for acceptance.
                if attempt==2 or not message.startswith('50001:') or '频繁' not in message:raise
                match=re.search(r'约 (\d+) 秒后可重试',message)
                delay=min(301,int(match.group(1))+1) if match else 301
                self.login_wait_seconds+=delay;time.sleep(delay)

def percentile(values,p):
    return sorted(values)[max(0,math.ceil(len(values)*p)-1)] if values else None

def main(duration,interval,workers,output):
    import crypt # Ubuntu's libcrypt BCrypt; no extra package or plaintext credentials on disk.
    evidence={'started':time.time(),'duration':duration,'interval':interval,'workers':workers,'tryRuns':[],'submissions':[],'resources':[]}
    stop=threading.Event();lock=threading.Lock();clients=[];ids=[];submission_ids=[];abort=[]
    initial_states=json.loads(subprocess.check_output(['docker','inspect',container('core','judge-service'),container('core','sandbox')]))
    initial_restarts={x['Name']:x['RestartCount'] for x in initial_states}
    def save():atomic_json(output,evidence)
    def sampler():
        previous=None
        while not stop.is_set():
            cpu=list(map(int,Path('/proc/stat').read_text().splitlines()[0].split()[1:]));total=sum(cpu);idle=cpu[3]+cpu[4]
            mem={l.split(':')[0]:int(l.split()[1]) for l in Path('/proc/meminfo').read_text().splitlines()}
            vm={l.split()[0]:int(l.split()[1]) for l in Path('/proc/vmstat').read_text().splitlines()}
            row={'at':time.time(),'availableMiB':round(mem['MemAvailable']/1024,1),'swapUsedMiB':round((mem['SwapTotal']-mem['SwapFree'])/1024,1),
                 'swapPagesIn':vm['pswpin'],'swapPagesOut':vm['pswpout'],'oomKills':vm.get('oom_kill',0),
                 'cpuPercent':None if previous is None else round(100*(1-(idle-previous[1])/max(1,total-previous[0])),1)}
            previous=(total,idle)
            try:
                queue=subprocess.check_output(['docker','exec',container('core','rabbitmq'),'rabbitmqctl','-q','list_queues','name','messages_ready','messages_unacknowledged'],timeout=15).decode().strip()
                row['queue']=queue
            except Exception:row['queue']='probe failed'
            with lock:evidence['resources'].append(row);save()
            if mem['MemAvailable']<200*1024:abort.append('Available host memory below 200 MiB');stop.set()
            stop.wait(5)
    def reads():
        api=Api();latencies=[];errors=0
        while not stop.is_set():
            start=time.monotonic()
            try:api.call('/question/list/page/vo',{'current':1,'pageSize':20,'sortField':'problemNo','sortOrder':'ascend'});latencies.append(time.monotonic()-start)
            except Exception:errors+=1
            stop.wait(.25)
        return latencies,errors
    def submit(sequence):
        api=clients[sequence%len(clients)];language=list(CODES)[sequence%3];start=time.monotonic();row={'language':language,'sequence':sequence}
        try:
            sid=str(api.call('/question/question_submit/do',{'questionId':3,'problemVersion':problem['problemVersion'],'language':language,'executionMode':'FUNCTION','code':CODES[language],'clientRequestId':str(uuid.uuid4())}))
            submission_ids.append(int(sid));row['ackSeconds']=round(time.monotonic()-start,3);row['id']=sid
            deadline=time.monotonic()+180
            while time.monotonic()<deadline:
                detail=api.call('/question/question_submit/get/vo?id='+sid)
                if detail['status'] in [2,3]:
                    row.update(totalSeconds=round(time.monotonic()-start,3),verdict=detail.get('verdict'),attemptCount=int(mysql('SELECT attemptCount FROM question_submit WHERE id='+sid+';')));break
                time.sleep(.75)
            else:row['error']='Terminal timeout'
        except Exception as error:row['error']=type(error).__name__+': '+str(error)[:180]
        with lock:evidence['submissions'].append(row);save()
        if row.get('error') or row.get('verdict')!='accepted':abort.append('Unexpected submission result');stop.set()
        return row
    try:
        for i in range(6):
            account='cloudperf_'+secrets.token_hex(6);password=secrets.token_hex(16);hashed=crypt.crypt(password,crypt.mksalt(crypt.METHOD_BLOWFISH))
            if not hashed or not hashed.startswith('$2'):raise RuntimeError('BCrypt unavailable')
            # All strings below use a restricted hexadecimal/BCrypt alphabet.
            uid=int(mysql("INSERT INTO user(userAccount,userPassword,userName,userRole) VALUES ('"+account+"','"+hashed+"','Cloud acceptance','user'); SELECT LAST_INSERT_ID();"))
            ids.append(uid);api=Api();api.login(account,password);clients.append(api)
        problem=clients[0].call('/question/get/vo/by-number?problemNo=3');before=mysql('SELECT COUNT(*) FROM question_submit;')
        evidence['problem']={'number':3,'version':problem['problemVersion'],'functionSampleCount':len(problem.get('functionSamples') or [])}
        for i,(language,mode) in enumerate((l,m) for l in CODES for m in ['ACM','FUNCTION']):
            body={'questionId':3,'problemVersion':problem['problemVersion'],'language':language,'executionMode':mode,'code':ACM[language] if mode=='ACM' else CODES[language]}
            if mode=='FUNCTION' and problem.get('functionSamples'):body['sampleIndex']=0
            else:body['input']='' if mode=='ACM' else '{"nums":[-1,0,3,5,9,12],"target":9}'
            started=time.monotonic();result=clients[i].call('/question/try_run',body);expected='42' if mode=='ACM' else (problem['functionSamples'][0]['output'] if 'sampleIndex' in body else '4')
            row={'language':language,'mode':mode,'verdict':result.get('verdict'),'seconds':round(time.monotonic()-started,3),'outputMatches':(result.get('output') or '').strip()==expected,'resourcesPresent':result.get('executedTime') is not None and result.get('executedMemory') is not None}
            evidence['tryRuns'].append(row);save()
            if not row['outputMatches'] or row['verdict']!='Completed':raise RuntimeError('Try-run output mismatch')
        evidence['tryRunStatsUnchanged']=before==mysql('SELECT COUNT(*) FROM question_submit;')
        # CE/RE/TLE use another user so each remains within the independent 3/min window.
        for i,(code,expected) in enumerate([('public class Main { invalid }','Compile Error'),('public class Main { public static void main(String[] a){throw new RuntimeException("test");}}','Runtime Error'),('public class Main { public static void main(String[] a){while(true){}}}','Time Limit Exceeded')]):
            result=clients[i].call('/question/try_run',{'questionId':3,'problemVersion':problem['problemVersion'],'language':'java','executionMode':'ACM','code':code,'input':''})
            evidence['tryRuns'].append({'expected':expected,'verdict':result['verdict'],'diagnosticRedacted':'/app/' not in (result.get('diagnostic') or '')});save()
            if result['verdict']!=expected:raise RuntimeError('Error verdict mismatch')
        with concurrent.futures.ThreadPoolExecutor(max_workers=workers+6) as pool:
            monitor=pool.submit(sampler);reader_futures=[pool.submit(reads) for _ in range(workers)];futures=[]
            deadline=time.monotonic()+duration;sequence=0
            while time.monotonic()<deadline and not stop.is_set():
                futures.append(pool.submit(submit,sequence));sequence+=1;stop.wait(interval)
            rows=[f.result(timeout=240) for f in futures];stop.set();read_batches=[f.result(timeout=70) for f in reader_futures];monitor.result(timeout=30)
        latencies=[x for batch,_ in read_batches for x in batch]
        completed=[r['totalSeconds'] for r in rows if 'totalSeconds' in r]
        evidence['summary']={'submitted':len(rows),'completed':len(completed),'accepted':sum(r.get('verdict')=='accepted' for r in rows),'errors':sum('error' in r for r in rows),
            'judgeP50':percentile(completed,.5),'judgeP95':percentile(completed,.95),'judgeMax':max(completed) if completed else None,
            'readRequests':len(latencies),'readErrors':sum(e for _,e in read_batches),'readP50':percentile(latencies,.5),'readP95':percentile(latencies,.95),'abort':abort}
    finally:
        stop.set();evidence['ended']=time.time()
        if ids:
            joined=','.join(map(str,ids));pending=int(mysql('SELECT COUNT(*) FROM question_submit WHERE userId IN ('+joined+') AND status IN(0,1) AND isDelete=0;'))
            evidence['pendingAfterRun']=pending
            if pending==0:
                # The same terminal-state statistics projection handles soft deletes.
                mysql("UPDATE question_submit SET isDelete=1 WHERE userId IN ("+joined+"); UPDATE user SET userRole='ban',isDelete=1 WHERE id IN ("+joined+");")
                evidence['fixturesRemoved']=True
            else:evidence['fixturesRemoved']=False
        final_states=json.loads(subprocess.check_output(['docker','inspect',container('core','judge-service'),container('core','sandbox')]))
        evidence['restartDelta']={x['Name']:x['RestartCount']-initial_restarts[x['Name']] for x in final_states}
        evidence['sandboxResidual']=len(subprocess.check_output(['docker','ps','-aq','--filter','label=nemo-oj.sandbox=true']).splitlines())
        save()
    print(json.dumps(evidence.get('summary',{})))

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--duration',type=int,default=300);parser.add_argument('--interval',type=float,default=10);parser.add_argument('--workers',type=int,default=4);parser.add_argument('--output',default='/home/ubuntu/cloud-acceptance-20261002.json')
    args=parser.parse_args();main(args.duration,args.interval,args.workers,args.output)
