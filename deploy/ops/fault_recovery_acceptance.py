"""Opt-in, sequential cloud fault drill. Only run after the load test has drained.

Restarts a running judge, briefly stops RabbitMQ, and interrupts the private AI
proxy. Restores every affected container in finally. Never changes lease times,
disables admission, or records user code/passwords. Real AI spending is retained.
"""
import crypt,json,secrets,subprocess,sys,time,uuid
sys.path.insert(0,'/usr/local/lib/nemo-ops')
from common import mysql,container,atomic_json
from cloud_acceptance import Api,ApiFailure,CODES

def control(action,cid,*options):
    subprocess.run(['docker',action,*options,cid],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,timeout=60)

def main(output):
    assert int(mysql('SELECT COUNT(*) FROM question_submit WHERE status IN(0,1) AND isDelete=0;'))==0
    assert int(mysql("SELECT COUNT(*) FROM ai_task WHERE status IN('QUEUED','RUNNING');"))==0
    ids={name:container('core',name) for name in ['judge-service','rabbitmq','ai-link-proxy']}
    account='fault_'+secrets.token_hex(6);password=secrets.token_hex(16)
    hashed=crypt.crypt(password,crypt.mksalt(crypt.METHOD_BLOWFISH))
    uid=int(mysql("INSERT INTO user(userAccount,userPassword,userName,userRole) VALUES ('"+account+"','"+hashed+"','Fault recovery acceptance','user');SELECT LAST_INSERT_ID();"))
    evidence={'started':time.time(),'fixtureUserId':uid,'scenarios':[]};atomic_json(output,evidence)
    api=Api(); submissions=[];task=None
    def submit(code):
        q=api.call('/question/get/vo/by-number?problemNo=3')
        body={'questionId':q['id'],'problemVersion':q['problemVersion'],'language':'python','executionMode':'FUNCTION','code':code,'clientRequestId':str(uuid.uuid4())}
        sid=str(api.call('/question/question_submit/do',body));submissions.append(sid);return sid,body
    def terminal(sid,timeout=240,test_age=False):
        began=time.monotonic();end=began+timeout
        while time.monotonic()<end:
            row=api.call('/question/question_submit/get/vo?id='+sid)
            if row['status'] in [2,3]:
                assert row.get('verdict')=='accepted',row.get('verdict')
                return row
            if test_age and time.monotonic()-began>=115 and not evidence.get('ageProtectionVerified'):
                age=int(mysql('SELECT TIMESTAMPDIFF(SECOND,createTime,NOW()) FROM question_submit WHERE id='+sid+';'))
                if age>=120:
                    q=api.call('/question/get/vo/by-number?problemNo=3')
                    try:
                        api.call('/question/question_submit/do',{'questionId':q['id'],'problemVersion':q['problemVersion'],'language':'python','executionMode':'FUNCTION','code':CODES['python'],'clientRequestId':str(uuid.uuid4())})
                        raise AssertionError('Oldest pending task did not suspend admission')
                    except ApiFailure as error:
                        assert error.status==503 and error.error_key=='JUDGE_BUSY'
                        evidence['ageProtectionVerified']=True
            time.sleep(1)
        raise AssertionError('Submission recovery timeout')
    try:
        api.login(account,password)
        slow=CODES['python'].replace('        l,r=', '        import time;time.sleep(1)\n        l,r=')
        assert slow!=CODES['python']
        sid,body=submit(slow)
        end=time.monotonic()+30
        while time.monotonic()<end:
            if mysql('SELECT status FROM question_submit WHERE id='+sid+';')=='1':break
            time.sleep(.05)
        assert mysql('SELECT status FROM question_submit WHERE id='+sid+';')=='1'
        started=time.monotonic();control('restart',ids['judge-service'],'-t','1')
        terminal(sid,test_age=True)
        attempts=int(mysql('SELECT attemptCount FROM question_submit WHERE id='+sid+';'))
        assert attempts==2
        assert str(api.call('/question/question_submit/do',body))==sid
        evidence['scenarios'].append({'fault':'judge_restart_during_execution','recoverySeconds':round(time.monotonic()-started,3),'attemptCount':attempts,'verdict':'accepted','idempotentReplay':True})
        atomic_json(output,evidence)
        print(json.dumps(evidence['scenarios'][-1]),flush=True)

        control('stop',ids['rabbitmq'],'-t','1')
        try:
            sid,_=submit(CODES['python'])
            assert mysql('SELECT status FROM question_submit WHERE id='+sid+';')=='0'
            assert int(mysql("SELECT COUNT(*) FROM outbox_event WHERE bizId="+sid+" AND status IN(0,1);"))==1
            time.sleep(8)
        finally:control('start',ids['rabbitmq'])
        started=time.monotonic();terminal(sid)
        attempts=int(mysql('SELECT attemptCount FROM question_submit WHERE id='+sid+';'));assert attempts==1
        delivery=mysql('SELECT status,retryCount FROM outbox_event WHERE bizId='+sid+';').split('\t')
        assert delivery[0]=='2'
        evidence['scenarios'].append({'fault':'rabbitmq_8_second_outage','recoverySeconds':round(time.monotonic()-started,3),'attemptCount':attempts,'outboxStatus':delivery[0],'deliveryRetries':int(delivery[1]),'verdict':'accepted'})
        atomic_json(output,evidence)
        print(json.dumps(evidence['scenarios'][-1]),flush=True)

        before=api.call('/question/ai/entitlements')['remainingToday']
        control('pause',ids['ai-link-proxy'])
        try:
            created=api.call('/question/ai/analyses',{'submissionId':sid,'idempotencyKey':str(uuid.uuid4())});task=created['taskId']
            time.sleep(8)
            blocked=api.call('/question/ai/tasks/'+task)
            assert blocked['status']!='SUCCEEDED'
        finally:control('unpause',ids['ai-link-proxy'])
        started=time.monotonic();end=started+240
        while time.monotonic()<end:
            row=api.call('/question/ai/tasks/'+task)
            if row['status'] in ['SUCCEEDED','FAILED','CANCELLED']:break
            time.sleep(1)
        assert row['status']=='SUCCEEDED',row.get('errorCode')
        after=api.call('/question/ai/entitlements')['remainingToday'];assert after==before-1
        calls=mysql("SELECT state,estimatedCost,reservedCost FROM ai_model_call WHERE taskId='"+task+"';").splitlines()
        assert len(calls)==1 and calls[0].split('\t')[0]=='KNOWN'
        evidence['scenarios'].append({'fault':'private_ai_link_8_second_interruption','recoverySeconds':round(time.monotonic()-started,3),'taskStatus':row['status'],'knownModelCalls':1,'quotaUsed':1,'usageLedgerPreserved':True})
        evidence['passed']=True
    finally:
        for name,cid in ids.items():
            state=json.loads(subprocess.check_output(['docker','inspect',cid]))[0]['State']
            if state.get('Paused'):control('unpause',cid)
            if not state.get('Running'):control('start',cid)
        end=time.monotonic()+240
        while time.monotonic()<end:
            pending=int(mysql('SELECT COUNT(*) FROM question_submit WHERE userId='+str(uid)+' AND status IN(0,1) AND isDelete=0;'))
            active=int(mysql("SELECT COUNT(*) FROM ai_task WHERE ownerUserId="+str(uid)+" AND status IN('QUEUED','RUNNING');"))
            if not pending and not active:break
            time.sleep(1)
        evidence['fixturesRemoved']=not pending and not active
        if evidence['fixturesRemoved']:mysql('UPDATE question_submit SET isDelete=1 WHERE userId='+str(uid)+";UPDATE user SET userRole='ban',isDelete=1 WHERE id="+str(uid)+';')
        evidence['containersRestored']=True;evidence['ended']=time.time();atomic_json(output,evidence)
    assert evidence.get('passed') and evidence['fixturesRemoved']
    print(json.dumps(evidence))

if __name__=='__main__':main(sys.argv[1])
