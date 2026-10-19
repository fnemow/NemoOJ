"""Signed synthetic call receipts and successful retry. No provider network calls."""
import crypt,json,secrets,uuid,time,hmac,hashlib,urllib.request,sys,subprocess
sys.path.insert(0,'/usr/local/lib/nemo-ops')
from common import mysql,container,env_file,atomic_json
from cloud_acceptance import Api,CODES

def main(output):
    env=env_file('/srv/nemo-oj/deploy/.env');account='call_ledger_'+secrets.token_hex(6);password=secrets.token_hex(16)
    hashed=crypt.crypt(password,crypt.mksalt(crypt.METHOD_BLOWFISH))
    uid=int(mysql("INSERT INTO user(userAccount,userPassword,userName,userRole) VALUES ('"+account+"','"+hashed+"','Call ledger acceptance','user'); SELECT LAST_INSERT_ID();"))
    task=str(uuid.uuid4());worker='ledger-'+secrets.token_hex(8);sid=None;evidence={'providerCalls':0,'fixtureUserId':uid,'started':time.time()}
    ip=next(v['IPAddress'] for v in json.loads(subprocess.check_output(['docker','inspect',container('core','question-service')]))[0]['NetworkSettings']['Networks'].values() if v['IPAddress'])
    def signed(suffix,data):
        path='/api/question/internal/ai/tasks/'+task+'/'+suffix;body=json.dumps(data,separators=(',',':')).encode();stamp=str(int(time.time()))
        sig=hmac.new(env['AI_INTERNAL_SECRET'].encode(),(stamp+'\nPOST\n'+path+'\n'+hashlib.sha256(body).hexdigest()).encode(),hashlib.sha256).hexdigest()
        request=urllib.request.Request('http://'+ip+':8103'+path,data=body,headers={'Content-Type':'application/json','X-Agent-Timestamp':stamp,'X-Agent-Signature':sig})
        with urllib.request.urlopen(request,timeout=30) as response:value=json.load(response)
        return value
    try:
        api=Api();api.login(account,password);problem=api.call('/question/get/vo/by-number?problemNo=3')
        sid=str(api.call('/question/question_submit/do',{'questionId':problem['id'],'problemVersion':problem['problemVersion'],'language':'python','executionMode':'FUNCTION','code':CODES['python'],'clientRequestId':str(uuid.uuid4())}))
        end=time.monotonic()+120
        while time.monotonic()<end:
            detail=api.call('/question/question_submit/get/vo?id='+sid)
            if detail['status'] in [2,3]:break
            time.sleep(.5)
        assert detail['verdict']=='accepted'
        mysql("INSERT INTO ai_task(id,type,ownerUserId,resourceId,idempotencyKey,status,leaseOwner,leaseExpireAt,attemptCount,modelId,promptVersion) VALUES ('"+task+"','ANALYSIS',"+str(uid)+",'"+sid+"','"+task+"','RUNNING','"+worker+"',DATE_ADD(NOW(),INTERVAL 5 MINUTE),1,'synthetic-ledger','acceptance'); INSERT INTO ai_usage(taskId,userId,usageDate,category,state,reservedCost) VALUES ('"+task+"',"+str(uid)+",CURRENT_DATE(),'ANALYSIS','RESERVED',"+env['AI_MAX_TASK_BUDGET_USD']+");")
        first,second=str(uuid.uuid4()),str(uuid.uuid4())
        assert signed('calls/'+first+'/start',{'workerId':worker})['data'] is True
        assert signed('calls/'+first+'/finish',{'workerId':worker,'state':'UNKNOWN','inputTokens':0,'outputTokens':0,'estimatedCostUsd':'0'})['data'] is True
        old_worker=worker;worker=worker+'-retry'
        mysql("UPDATE ai_task SET attemptCount=2,leaseOwner='"+worker+"' WHERE id='"+task+"';")
        assert signed('calls/'+second+'/start',{'workerId':worker})['data'] is True
        receipt={'workerId':worker,'state':'KNOWN','inputTokens':100,'outputTokens':20,'estimatedCostUsd':'0.001'}
        assert signed('calls/'+second+'/finish',receipt)['data'] is True
        assert signed('calls/'+second+'/finish',receipt)['data'] is True
        report={'verdictEvidence':{'verdict':'ACCEPTED'},'likelyCause':'Synthetic receipt verification','locations':[],
                'suggestions':[],'complexity':{'time':'O(log n)','space':'O(1)'},'uncertainty':'Synthetic ledger test only'}
        completed={'workerId':worker,'report':report,'inputTokens':100,'outputTokens':20,'estimatedCostUsd':.001}
        assert signed('complete',{**completed,'workerId':old_worker})['code']!=0
        assert signed('complete',completed)['data'] is True
        assert signed('complete',completed)['data'] is True
        usage=mysql("SELECT state,inputTokens,outputTokens,estimatedCost,reservedCost FROM ai_usage WHERE taskId='"+task+"';").split('\t')
        assert usage[0]=='COMMITTED' and usage[1:3]==['100','20'] and float(usage[3])==.001 and float(usage[4])==float(env['AI_MAX_TASK_BUDGET_USD']),usage
        quota=int(mysql('SELECT COUNT(*) FROM ai_usage WHERE userId='+str(uid)+" AND state IN('RESERVED','COMMITTED');"));assert quota==1
        stale=signed('calls/'+str(uuid.uuid4())+'/start',{'workerId':'other-worker'});assert stale['code']!=0
        evidence.update(passed=True,receipts=2,states=['UNKNOWN','KNOWN'],leaseAttempts=[1,2],usageState=usage[0],knownCost=usage[3],uncertainReservation=usage[4],duplicateReceiptsIdempotent=True,duplicateCompletionIdempotent=True,quotaUsed=quota,staleWorkerDenied=True,staleCompletionDenied=True)
    finally:
        # Only synthetic receipts are deleted; real provider spending is retained elsewhere.
        mysql("DELETE FROM ai_analysis WHERE userId="+str(uid)+"; DELETE FROM ai_action_audit WHERE taskId='"+task+"'; DELETE FROM ai_model_call WHERE taskId='"+task+"'; DELETE FROM ai_usage WHERE taskId='"+task+"'; DELETE FROM ai_task WHERE id='"+task+"';")
        pending=int(mysql('SELECT COUNT(*) FROM question_submit WHERE userId='+str(uid)+' AND status IN(0,1) AND isDelete=0;'))
        if pending==0:mysql('UPDATE question_submit SET isDelete=1 WHERE userId='+str(uid)+";UPDATE user SET userRole='ban',isDelete=1 WHERE id="+str(uid)+';')
        evidence.update(fixturesRemoved=pending==0,ended=time.time());atomic_json(output,evidence)
    print(json.dumps(evidence))

if __name__=='__main__':main(sys.argv[1])
