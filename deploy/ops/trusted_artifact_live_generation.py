"""Normal VIP generation and private formal submissions; no credentials in evidence."""
import crypt,json,secrets,sys,time,uuid
from pathlib import Path
sys.path.insert(0,'/usr/local/lib/nemo-ops');from common import mysql as raw_mysql,atomic_json
sys.path.insert(0,'/home/ubuntu/nemo-catalog-306-20261011');from catalog_306_release import Api
stage=Path(sys.argv[1]);report={'samples':[],'passed':False};accounts=[];apis=[]
def mysql(sql):return raw_mysql('SET NAMES utf8mb4;\n'+sql)
def quote(v):return "'"+str(v).replace("'","''")+"'"
try:
    for i in range(2):
        account='artifact_eval_'+secrets.token_hex(5);password=secrets.token_hex(16);hashed=crypt.crypt(password,crypt.mksalt(crypt.METHOD_BLOWFISH))
        uid=int(mysql('INSERT INTO user(userAccount,userPassword,userName,userRole) VALUES ('+quote(account)+','+quote(hashed)+",'验证缓存验收','user');SELECT LAST_INSERT_ID();"));accounts.append(uid)
        mysql("INSERT INTO user_membership(userId,tier,validFrom,validUntil,updatedBy) VALUES ("+str(uid)+",'VIP',NOW(),DATE_ADD(NOW(),INTERVAL 1 DAY),'artifact-acceptance')")
        api=Api();api.call('/user/login',{'userAccount':account,'userPassword':password});api.csrf=None;apis.append(api)
    for i,goal in enumerate(['给定非空整数矩阵matrix和多组queries，每项是[top,left,bottom,right]，返回闭区间矩形元素和数组，按查询顺序。矩阵没有修改，练习二维前缀和。','学习红黑树']):
        api=apis[i];start=time.monotonic();job=api.call('/question/ai/private-problems',{'goal':goal,'difficulty':'HARD','idempotencyKey':str(uuid.uuid4())});task=job['taskId'];deadline=time.monotonic()+300
        while time.monotonic()<deadline:
            status=api.call('/question/ai/tasks/'+task)
            if status['status'] in ('SUCCEEDED','FAILED','CANCELLED'):break
            time.sleep(.3)
        assert status['status']=='SUCCEEDED','Generation not successful'
        item=api.call('/question/ai/private-problems/'+job['privateProblemId']);version=item['currentVersion']['id']
        proof=json.loads(bytes.fromhex(mysql('SELECT HEX(validationJson) FROM private_problem_version WHERE id='+quote(version))).decode())
        assert proof.get('completeProofReused') and proof['runtimeValidated'] and proof['oracleValidated'] and len(proof['combinations'])==6
        calls=int(mysql('SELECT COUNT(*) FROM ai_model_call WHERE taskId='+quote(task)));assert calls==(1 if i==0 else 0)
        row={'topic':'prefix_matrix' if i==0 else 'redblack_hard','seconds':round(time.monotonic()-start,3),'modelCalls':calls,'completeProofReused':True,'attemptCount':int(mysql('SELECT attemptCount FROM ai_task WHERE id='+quote(task)))}
        assert row['attemptCount']==1 and 'referenceSolutions' not in item
        other=apis[1-i]
        try:other.call('/question/ai/private-problems/'+job['privateProblemId'])
        except RuntimeError as error:assert str(error).startswith('API rejected ');row['foreignReadRejected']=True
        else:raise AssertionError('Foreign read accepted')
        fixture=json.loads((stage/'prevalidation-requests.json').read_text())['prefix_matrix' if i==0 else 'redblack_hard']
        row['privateSubmissions']=[]
        for language in ('java','python','cpp'):
            source=fixture['referenceSolutions'][language+':FUNCTION'];submitted=time.monotonic()
            sid=api.call('/question/ai/private-problems/'+job['privateProblemId']+'/submissions',{'problemVersion':version,'language':language,'executionMode':'FUNCTION','code':source,'clientRequestId':str(uuid.uuid4())})
            until=time.monotonic()+120
            while time.monotonic()<until:
                value=api.call('/question/question_submit/get/vo?id='+str(sid))
                if value['status'] in (2,3):break
                time.sleep(.5)
            info=value.get('judgeInfo') or {};assert (value.get('verdict') or info.get('message'))=='accepted'
            assert info.get('executedTime') is not None and info.get('executedMemory') is not None
            row['privateSubmissions'].append({'language':language,'verdict':'accepted','resourcesPresent':True,'seconds':round(time.monotonic()-submitted,3)})
        report['samples'].append(row);print(json.dumps(row),flush=True)
    report['passed']=True
finally:
    ids=','.join(str(u) for u in accounts)
    pending=(int(mysql("SELECT COUNT(*) FROM ai_task WHERE ownerUserId IN ("+ids+") AND status IN ('QUEUED','RUNNING')"))+int(mysql('SELECT COUNT(*) FROM question_submit WHERE userId IN ('+ids+') AND status IN (0,1)'))) if ids else 0
    if not pending:
        for api in apis:
            try:api.call('/user/logout',{})
            except Exception:pass
        if ids:mysql("START TRANSACTION;UPDATE question_submit SET isDelete=1 WHERE userId IN ("+ids+");UPDATE private_problem SET status='ARCHIVED' WHERE ownerUserId IN ("+ids+");UPDATE user_membership SET validUntil=NOW() WHERE userId IN ("+ids+");UPDATE user SET userRole='ban',isDelete=1 WHERE id IN ("+ids+");COMMIT;")
    report['cleanupComplete']=not pending;atomic_json(stage/'live-generation.json',report)
print(json.dumps(report),flush=True)
