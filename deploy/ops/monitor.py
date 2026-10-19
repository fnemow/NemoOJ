"""Small-host monitoring with delayed, deduplicated SMTP alerts and recovery notices."""
import argparse
import json
import shutil
import smtplib
import socket
import ssl
import subprocess
import time
from email.message import EmailMessage
from pathlib import Path
from common import PROJECTS, atomic_json, env_file, mysql, run, send_remote

def collect(role):
    issues={}
    raw=run(['docker','ps','-aq','--filter','label=com.docker.compose.project='+PROJECTS[role][1]],stdout=subprocess.PIPE).stdout.decode().split()
    if not raw: issues['containers']='No deployment containers found'
    else:
        containers=json.loads(run(['docker','inspect',*raw],stdout=subprocess.PIPE).stdout)
        found=set()
        for item in containers:
            name=item['Config']['Labels'].get('com.docker.compose.service','unknown')
            found.add(name)
            state=item['State']
            if role=='core' and name in ['nacos','proxy'] and not state['Running']:
                continue # Optional legacy/public profiles are inactive during SSH acceptance.
            if not state['Running'] or state.get('Health',{}).get('Status')=='unhealthy': issues['service-'+name]='Service is stopped or unhealthy'
        expected={'mysql','redis','rabbitmq','gateway','user-service','question-service','judge-service','sandbox','ai-link-proxy','preview-proxy'} if role=='core' else {'nemo-agent','ai-postgres'}
        for missing in expected-found: issues['service-'+missing]='Required service container is missing'
    memory=dict((line.split(':')[0],int(line.split()[1])) for line in Path('/proc/meminfo').read_text().splitlines())
    if memory['MemAvailable']/memory['MemTotal']<0.1: issues['memory']='Available host memory below 10%'
    disk=shutil.disk_usage('/')
    if disk.used/disk.total>0.85: issues['disk']='Disk usage exceeds 85%'
    status=Path('/var/backups/nemo-oj')/role/'status.json'
    if not status.exists() or time.time()-json.loads(status.read_text()).get('successAt',0)>26*3600: issues['backup']='Verified off-host backup is missing or older than 26 hours'
    if role=='core':
        try:
            from redis_session_ops import RedisConnection, memory_sample
            with RedisConnection() as redis_client:
                redis = memory_sample(redis_client)
            if redis['usedPercent'] is not None and redis['usedPercent'] >= 80:
                issues['redis-memory'] = 'Redis memory usage exceeds 80% of maxmemory'
        except Exception:
            redis = None
            issues['redis-probe'] = 'Redis memory probe failed'
        pending=mysql('SELECT COUNT(*) FROM question_submit WHERE isDelete=0 AND status IN(0,1) AND createTime<DATE_SUB(NOW(),INTERVAL 2 MINUTE);')
        if int(pending)>0: issues['judge-wait']='Judging tasks have waited over 2 minutes'
        failed=mysql("SELECT COUNT(*) FROM outbox_event WHERE status=3;")
        if int(failed)>0: issues['outbox']='Undelivered terminal Outbox events exist'
        env=env_file('/srv/nemo-oj/deploy/.env');limit=float(env.get('AI_DAILY_BUDGET_USD','0'))
        spend=float(mysql("SELECT (SELECT COALESCE(SUM(estimatedCost+reservedCost),0) FROM ai_usage u "
                          "WHERE state IN ('RESERVED','COMMITTED','RELEASED') AND usageDate=DATE(CONVERT_TZ(UTC_TIMESTAMP(),'+00:00','+08:00')) "
                          "AND NOT EXISTS(SELECT 1 FROM ai_model_call c WHERE c.taskId=u.taskId)) + "
                          "(SELECT COALESCE(SUM(estimatedCost+reservedCost),0) FROM ai_model_call "
                          "WHERE usageDate=DATE(CONVERT_TZ(UTC_TIMESTAMP(),'+00:00','+08:00')));"))
        if limit>0 and spend>=0.8*limit: issues['ai-budget']='AI committed and reserved spending exceeds 80% of the daily budget'
        remote=Path('/var/lib/nemo-backup/ai/latest-status.json')
        if not remote.exists() or time.time()-json.loads(remote.read_text()).get('receivedAt',0)>240: issues['ai-link']='AI monitoring heartbeat is missing for over 4 minutes'
        else:
            for key,value in json.loads(remote.read_text()).get('issues',{}).items(): issues['ai-'+key]=str(value)[:250]
    pressure={}
    for resource in ['cpu','memory','io']:
        source=Path('/proc/pressure')/resource
        if source.exists():
            pressure[resource]={line.split()[0]:dict(part.split('=',1) for part in line.split()[1:])
                                for line in source.read_text().splitlines()}
    vm=dict(line.split() for line in Path('/proc/vmstat').read_text().splitlines())
    return {'role':role,'at':time.time(),'issues':issues,'redis':redis if role=='core' else None,'availableMemoryMiB':memory['MemAvailable']//1024,
            'swapUsedMiB':(memory['SwapTotal']-memory['SwapFree'])//1024,
            'swapPagesIn':int(vm.get('pswpin',0)),'swapPagesOut':int(vm.get('pswpout',0)),
            'pressure':pressure,'diskUsedPercent':round(100*disk.used/disk.total,1)}

def transitions(previous, issues, now, delay=120):
    active={};new=[];recovered=[]
    for key,message in issues.items():
        value=previous.get(key,{'firstSeen':now,'notified':False})
        if now-value['firstSeen']>=delay and not value['notified']: new.append((key,message))
        active[key]=value
    for key,value in previous.items():
        if key not in issues and value.get('notified'): recovered.append(key)
    return active,new,recovered

def notify(new, recovered):
    env=env_file('/srv/nemo-oj/deploy/.env')
    required=['SMTP_HOST','SMTP_USERNAME','SMTP_PASSWORD','AI_ESCALATION_EMAIL']
    if not all(env.get(k) for k in required): raise RuntimeError('SMTP configuration incomplete')
    message=EmailMessage();message['Subject']='[NemoOJ operations] '+('Alert' if new else 'Recovered')
    message['From']=env.get('SMTP_FROM') or env['SMTP_USERNAME'];message['To']=env['AI_ESCALATION_EMAIL']
    body=['Host: '+socket.gethostname(),*[key+': '+text for key,text in new],*['Recovered: '+key for key in recovered]]
    message.set_content('\n'.join(body))
    secure=env.get('SMTP_SSL','false').lower()=='true';port=int(env.get('SMTP_PORT','465' if secure else '587'))
    smtp=smtplib.SMTP_SSL(env['SMTP_HOST'],port,timeout=15,context=ssl.create_default_context()) if secure else smtplib.SMTP(env['SMTP_HOST'],port,timeout=15)
    with smtp:
        if not secure and env.get('SMTP_STARTTLS','true').lower()=='true': smtp.starttls(context=ssl.create_default_context())
        smtp.login(env['SMTP_USERNAME'],env['SMTP_PASSWORD']);smtp.send_message(message)

def monitor(role):
    import fcntl
    directory=Path('/var/lib/nemo-ops');directory.mkdir(parents=True,exist_ok=True,mode=0o700)
    with (directory/(role+'-monitor.lock')).open('a') as lock:
        try: fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:
            print('Monitoring cycle already running');return
        monitor_once(role)

def monitor_once(role):
    try: sample=collect(role)
    except Exception: sample={'role':role,'at':time.time(),'issues':{'probe':'Operational probe failed'}}
    path=Path('/var/lib/nemo-ops')/(role+'-monitor.json')
    previous=json.loads(path.read_text()) if path.exists() else {'active':{}}
    active,new,recovered=transitions(previous.get('active',{}),sample['issues'],time.time())
    if role=='core' and (new or recovered):
        try:
            notify(new,recovered)
            for key,_ in new: active[key]['notified']=True
        except Exception:
            print('SMTP delivery failed; the next monitoring cycle will retry')
            # Keep recovery events until their notification is delivered.
            for key in recovered: active[key]=previous['active'][key]
    if role=='ai':
        try: send_remote(b'STATUS',json.dumps(sample).encode())
        except Exception: sample['issues']['heartbeat']='Status delivery failed'
    atomic_json(path,{'active':active,'sample':sample})
    print(json.dumps(sample))

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--role',choices=PROJECTS,required=True)
    monitor(parser.parse_args().role)
