"""Normal VIP generation through HTTPS; temporary credentials never leave host memory."""
import crypt,json,secrets,sys,time,uuid
from pathlib import Path
sys.path.insert(0,'/usr/local/lib/nemo-ops');from common import mysql as raw_mysql,atomic_json
sys.path.insert(0,'/home/ubuntu/nemo-catalog-306-20261011');from catalog_306_release import Api

stage=Path(sys.argv[1]);difficulty=sys.argv[2];assert difficulty in ('EASY','MEDIUM','HARD')
def mysql(query):return raw_mysql('SET NAMES utf8mb4;\n'+query)
def quote(value):return "'"+str(value).replace("'","''")+"'"
account='rb_eval_'+secrets.token_hex(5);password=secrets.token_hex(16)
hashed=crypt.crypt(password,crypt.mksalt(crypt.METHOD_BLOWFISH))
uid=int(mysql('INSERT INTO user(userAccount,userPassword,userName,userRole) VALUES ('+quote(account)+','+quote(hashed)+",'红黑树生成验收','user');SELECT LAST_INSERT_ID();"))
mysql("INSERT INTO user_membership(userId,tier,validFrom,validUntil,updatedBy) VALUES ("+str(uid)+",'VIP',NOW(),DATE_ADD(NOW(),INTERVAL 1 DAY),'redblack-acceptance')")
api=Api();report={'difficulty':difficulty,'goal':'红黑树','passed':False}
try:
    api.call('/user/login',{'userAccount':account,'userPassword':password});api.csrf=None
    start=time.monotonic();job=api.call('/question/ai/private-problems',{'goal':'学习红黑树','difficulty':difficulty,'idempotencyKey':str(uuid.uuid4())})
    task=job['taskId'];deadline=time.monotonic()+920;previous=None
    while time.monotonic()<deadline:
        status=api.call('/question/ai/tasks/'+task)
        current=(status['status'],status.get('stage'))
        if previous!=current:
            print(json.dumps({'difficulty':difficulty,'status':current[0],'stage':current[1],'elapsedSeconds':round(time.monotonic()-start,1)}),flush=True);previous=current
        if status['status'] in ('SUCCEEDED','FAILED','CANCELLED'):break
        time.sleep(2)
    else:raise RuntimeError('Task has not reached a terminal state; cleanup deferred')
    report.update(status=status['status'],seconds=round(time.monotonic()-start,3))
    raw=mysql('SELECT HEX(JSON_OBJECT(\'errorCode\',errorCode,\'attemptCount\',attemptCount)) FROM ai_task WHERE id='+quote(task))
    report.update(json.loads(bytes.fromhex(raw).decode()))
    usage=mysql('SELECT COUNT(*),COALESCE(SUM(inputTokens),0),COALESCE(SUM(outputTokens),0) FROM ai_model_call WHERE taskId='+quote(task)).split('\t')
    report.update(modelCalls=int(usage[0]),inputTokens=int(usage[1]),outputTokens=int(usage[2]))
    if status['status']=='SUCCEEDED':
        item=api.call('/question/ai/private-problems/'+job['privateProblemId']);version=item['currentVersion']['id']
        proof=json.loads(bytes.fromhex(mysql('SELECT HEX(validationJson) FROM private_problem_version WHERE id='+quote(version))).decode())
        assert proof['runtimeValidated'] and proof['oracleValidated'] and len(proof['combinations'])==6
        assert 'referenceSolutions' not in item
        report.update(passed=True,sixCombinations=True,oracleValidated=True,title=item['title'],reportedDifficulty=item['difficulty'])
    else:
        raw=mysql("SELECT HEX(JSON_OBJECT('status',status,'category',JSON_EXTRACT(resultJson,'$.category'),'combination',JSON_EXTRACT(resultJson,'$.combination'))) FROM private_validation_job WHERE taskId="+quote(task)+' ORDER BY revision')
        report['validationStages']=[json.loads(bytes.fromhex(line).decode()) for line in raw.splitlines() if line]
finally:
    pending=int(mysql('SELECT COUNT(*) FROM ai_task WHERE ownerUserId='+str(uid)+" AND status IN ('QUEUED','RUNNING')"))
    if pending:
        report['cleanupComplete']=False
    else:
        try:api.call('/user/logout',{})
        except Exception:pass
        mysql("START TRANSACTION;UPDATE private_problem SET status='ARCHIVED' WHERE ownerUserId="+str(uid)+";UPDATE user_membership SET validUntil=NOW() WHERE userId="+str(uid)+";UPDATE user SET userRole='ban',isDelete=1 WHERE id="+str(uid)+";DELETE c FROM private_validation_checkpoint c JOIN private_validation_job v ON v.id=c.validationId JOIN ai_task t ON t.id=v.taskId WHERE t.ownerUserId="+str(uid)+";COMMIT;")
        report['cleanupComplete']=True
    atomic_json(stage/('live-'+difficulty.lower()+'.json'),report)
print(json.dumps(report,ensure_ascii=False),flush=True)
