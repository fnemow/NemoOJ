"""Full judge-chain smoke test: 42 AC plus CE/RE/WA controls; no model calls."""
import concurrent.futures
import crypt
import json
import queue
import secrets
import sys
import threading
import time
import uuid
from pathlib import Path
from catalog_306_release import Api,old_fingerprint
sys.path.insert(0,'/usr/local/lib/nemo-ops')
from common import mysql,atomic_json,container


def main(stage,resume=False):
    fixtures=json.loads((stage/'smoke-fixtures.json').read_text());jobs=queue.Queue()
    report={'results':[],'failures':[],'passed':False};target=stage/'judge-report.json'
    if target.exists() and resume:
        report=json.loads(target.read_text())
        report.setdefault('previousFailures',[]).extend(report['failures']);report['failures']=[]
    elif target.exists():
        atomic_json(stage/'judge-initial-report.json',json.loads(target.read_text()))
    completed={(row['problemNo'],row['language'],row['mode'],row['expected']) for row in report['results']}
    for item in fixtures:
        if (item['problemNo'],item['language'],item['mode'],item['expected']) not in completed:jobs.put(item)
    lock=threading.Lock();users=[]
    def save():
        with lock:atomic_json(target,report)
    def worker(api):
        while True:
            try:item=jobs.get_nowait()
            except queue.Empty:return
            number=item['problemNo'];started=time.monotonic();sid=None
            try:
                problem=api.call('/question/get/vo/by-number?problemNo='+str(number))
                body={'questionId':problem['id'],'problemVersion':problem['problemVersion'],
                    'language':item['language'],'executionMode':item['mode'],'code':item['code'],'clientRequestId':str(uuid.uuid4())}
                until=time.monotonic()+90
                while True:
                    try:sid=api.call('/question/question_submit/do',body);break
                    except RuntimeError as error:
                        if 'JUDGE_BUSY' not in str(error) or time.monotonic()>until:raise
                        report['capacityRejections']=report.get('capacityRejections',0)+1;time.sleep(5)
                until=time.monotonic()+180
                while time.monotonic()<until:
                    result=api.call('/question/question_submit/get/vo?id='+str(sid))
                    if result['status'] in (2,3):break
                    time.sleep(.5)
                else:raise RuntimeError('Submission deadline exceeded')
                verdict=result.get('verdict') or (result.get('judgeInfo') or {}).get('message')
                assert result['status'] in (2,3) and verdict==item['expected'],(number,item['language'],item['mode'],verdict,result.get('errorDetail'))
                info=result.get('judgeInfo') or {}
                if item['expected']=='accepted':assert info.get('executedTime') is not None and info.get('executedMemory') is not None
                attempts=int(mysql('SELECT attemptCount FROM question_submit WHERE id='+str(sid)));assert attempts==1
                report['results'].append({'problemNo':number,'language':item['language'],'mode':item['mode'],'expected':item['expected'],
                                          'verdict':verdict,'seconds':round(time.monotonic()-started,3),'resourcesPresent':info.get('executedMemory') is not None,'attemptCount':attempts})
                print(json.dumps({'judgeChecksPassed':len(report['results']),'total':len(fixtures),'problemNo':number,'language':item['language'],'mode':item['mode'],'verdict':verdict}),flush=True)
            except Exception as error:
                report['failures'].append({'problemNo':number,'language':item['language'],'mode':item['mode'],'error':str(error)[:1500]})
                print(json.dumps({'failedProblem':number,'language':item['language'],'mode':item['mode'],'error':str(error)[:300]}),flush=True)
            finally:save();jobs.task_done()
    try:
        clients=[]
        for _ in range(2):
            account='catalog306_test_'+secrets.token_hex(6);password=secrets.token_hex(16)
            hashed=crypt.crypt(password,crypt.mksalt(crypt.METHOD_BLOWFISH))
            uid=int(mysql("INSERT INTO user(userAccount,userPassword,userName,userRole) VALUES ('"+account+"','"+hashed+"','Catalog 306 acceptance','user'); SELECT LAST_INSERT_ID();"));users.append(uid)
            api=Api();api.call('/user/login',{'userAccount':account,'userPassword':password});api.csrf=None;clients.append(api)
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            futures=[pool.submit(worker,api) for api in clients]
            for future in futures:future.result()
        report['passed']=len(report['results'])==45 and not report['failures']
    finally:
        if users:
            ids=','.join(map(str,users));pending=int(mysql('SELECT COUNT(*) FROM question_submit WHERE userId IN ('+ids+') AND status IN (0,1) AND isDelete=0'))
            report['pendingTestSubmissions']=pending
            if pending==0:
                accounts=mysql('SELECT userAccount FROM user WHERE id IN ('+ids+')').splitlines()
                assert len(accounts)==len(users) and all(account.startswith('catalog306_test_') for account in accounts)
                mysql("START TRANSACTION; UPDATE question_submit SET isDelete=1 WHERE userId IN ("+ids+"); UPDATE user SET userRole='ban',isDelete=1 WHERE id IN ("+ids+"); COMMIT;")
                report['testAccountsRemoved']=True
            else:report['cleanupDeferred']=True
        audit=Path('/srv/nemo-oj/deploy/migration/checks/submission_statistics_audit.sql')
        report['statisticsMismatches']=len(mysql(audit.read_text()).splitlines())
        report['old166Unchanged']=old_fingerprint()==json.loads((stage/'publish-report.json').read_text())['oldFingerprint']
        save()
    assert report['passed'] and report['statisticsMismatches']==0 and report['old166Unchanged'],report['failures']
    print(json.dumps({'passed':True,'accepted':42,'negativeControls':3,'testAccountsRemoved':True,'statisticsMismatches':0}),flush=True)


if __name__=='__main__':main(Path(sys.argv[1]),'--resume' in sys.argv[2:])
