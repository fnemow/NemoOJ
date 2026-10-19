"""Real global backlog admission/recovery. Run only with an initially idle judge."""
import concurrent.futures,crypt,json,secrets,subprocess,sys,threading,time,uuid
from common import mysql,container,atomic_json
from cloud_acceptance import Api,ApiFailure,CODES

def main(output):
    assert int(mysql('SELECT COUNT(*) FROM question_submit WHERE status IN(0,1) AND isDelete=0;'))==0
    judge=container('core','judge-service');users=[];rows=[];apis=[];locks=[];evidence={'started':time.time()}
    atomic_json(output,evidence)
    initial=[]
    try:
        for _ in range(8):
            account='admission_'+secrets.token_hex(6);password=secrets.token_hex(16)
            hashed=crypt.crypt(password,crypt.mksalt(crypt.METHOD_BLOWFISH))
            uid=int(mysql("INSERT INTO user(userAccount,userPassword,userName,userRole) VALUES ('"+account+"','"+hashed+"','Admission acceptance','user'); SELECT LAST_INSERT_ID();"))
            users.append(uid);api=Api();api.login(account,password);apis.append(api);locks.append(threading.Lock())
        evidence['fixtureIds']=users;atomic_json(output,evidence)
        problem=apis[0].call('/question/get/vo/by-number?problemNo=3')
        slow=CODES['python'].replace('        l,r=', '        import time;time.sleep(1.5)\n        l,r=')
        for i in range(2):
            initial.append(str(apis[i].call('/question/question_submit/do',{'questionId':problem['id'],
                'problemVersion':problem['problemVersion'],'language':'python','executionMode':'FUNCTION',
                'code':slow,'clientRequestId':str(uuid.uuid4())})))
        before=int(mysql('SELECT COUNT(*) FROM question_submit;'))
        def submit(n):
            i=n%len(apis);body={'questionId':problem['id'],'problemVersion':problem['problemVersion'],
                               'executionMode':'FUNCTION','language':'python','code':CODES['python'],'clientRequestId':str(uuid.uuid4())}
            with locks[i]:
                try:
                    sid=str(apis[i].call('/question/question_submit/do',body));return {'id':sid,'user':i,'request':body,'accepted':True}
                except ApiFailure as e:return {'accepted':False,'status':e.status,'key':e.error_key,'retryAfter':e.retry_after,'message':str(e)}
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:rows=list(pool.map(submit,range(16)))
        accepted=[r for r in rows if r['accepted']];rejected=[r for r in rows if not r['accepted']]
        pending=int(mysql('SELECT COUNT(*) FROM question_submit WHERE status IN(0,1) AND isDelete=0;'))
        assert len(accepted)==10 and pending==12 and len(rejected)==6,(len(accepted),pending,rejected)
        assert all(r['status']==503 and r['key']=='JUDGE_BUSY' and r['retryAfter']=='5' for r in rejected)
        assert int(mysql('SELECT COUNT(*) FROM question_submit;'))==before+10
        replay=accepted[0]
        assert str(apis[replay['user']].call('/question/question_submit/do',replay['request']))==replay['id']
        assert int(mysql('SELECT COUNT(*) FROM question_submit WHERE status IN(0,1) AND isDelete=0;'))==12
        evidence.update(cap=12,initialSlowJobs=2,received=16,accepted=10,rejected=6,rejectedBeforeInsert=True,retryAfterSeconds=5,idempotentReplayAtCapacity=True)
    finally:
        if users:
            ids=','.join(map(str,users));end=time.monotonic()+180
            while time.monotonic()<end and int(mysql('SELECT COUNT(*) FROM question_submit WHERE userId IN ('+ids+') AND status IN(0,1) AND isDelete=0;')):time.sleep(1)
            remaining=int(mysql('SELECT COUNT(*) FROM question_submit WHERE userId IN ('+ids+') AND status IN(0,1) AND isDelete=0;'))
            states=mysql('SELECT status,attemptCount FROM question_submit WHERE userId IN ('+ids+');')
            evidence['completedOnce']=all(line=='2\t1' for line in states.splitlines()) and len(states.splitlines())==12
            evidence['pendingAfterRecovery']=remaining
            evidence['fixturesRemoved']=remaining==0
            if remaining==0:mysql('UPDATE question_submit SET isDelete=1 WHERE userId IN ('+ids+"); UPDATE user SET userRole='ban',isDelete=1 WHERE id IN ("+ids+');')
        evidence['ended']=time.time();atomic_json(output,evidence)
    assert evidence['pendingAfterRecovery']==0 and evidence['completedOnce']
    evidence['passed']=True;atomic_json(output,evidence);print(json.dumps(evidence))

if __name__=='__main__':main(sys.argv[1])
