"""Bounded real-model workflow acceptance; credentials and generated source stay on host."""
import crypt,json,secrets,sys,time,uuid
from pathlib import Path
sys.path.insert(0,'/usr/local/lib/nemo-ops');from common import mysql as raw_mysql,atomic_json
sys.path.insert(0,'/home/ubuntu/nemo-catalog-306-20261011');from catalog_306_release import Api
stage=Path(sys.argv[1]);report={'results':[],'allThreeWaitingObserved':False,'cleanupComplete':False}
def mysql(sql):return raw_mysql('SET NAMES utf8mb4;\n'+sql)
def quote(value):return "'"+str(value).replace("'","''")+"'"
def save():atomic_json(stage/'real-generation.json',report)
account='eval5_'+secrets.token_hex(6);password=secrets.token_hex(16);hashed=crypt.crypt(password,crypt.mksalt(crypt.METHOD_BLOWFISH))
uid=int(mysql("INSERT INTO user(userAccount,userPassword,userName,userRole) VALUES ("+quote(account)+','+quote(hashed)+",'第五轮生成验收','user');SELECT LAST_INSERT_ID();"))
mysql("INSERT INTO user_membership(userId,tier,validFrom,validUntil,updatedBy) VALUES ("+str(uid)+",'VIP',NOW(),DATE_ADD(NOW(),INTERVAL 1 DAY),'round5-acceptance')")
api=Api();tasks=[]
def create(goal,difficulty,expected):
    started=time.monotonic();task=api.call('/question/ai/private-problems',{'goal':goal,'difficulty':difficulty,'idempotencyKey':str(uuid.uuid4())})
    tasks.append((task,started,expected));return task
def collect(item):
    task,started,expected=item;tid=task['taskId'];status=api.call('/question/ai/tasks/'+tid)
    if status['status'] not in ('SUCCEEDED','FAILED','CANCELLED'):return False
    usage=mysql('SELECT COUNT(*),COALESCE(SUM(inputTokens),0),COALESCE(SUM(outputTokens),0) FROM ai_model_call WHERE taskId='+quote(tid)).split('\t')
    row={'taskId':tid,'status':status['status'],'seconds':round(time.monotonic()-started,3),'modelCalls':int(usage[0]),'inputTokens':int(usage[1]),'outputTokens':int(usage[2]),'expectedTemplate':expected,
         'attemptCount':int(mysql('SELECT attemptCount FROM ai_task WHERE id='+quote(tid))),
         'resumeEvents':int(mysql('SELECT COUNT(*) FROM ai_outbox WHERE taskId='+quote(tid)+" AND eventType LIKE 'VALIDATION_%'"))}
    assert status['status']=='SUCCEEDED', 'Real generation failed: '+str(status.get('errorCode'))
    p=api.call('/question/ai/private-problems/'+task['privateProblemId']);version=p['currentVersion']['id']
    proof=json.loads(bytes.fromhex(mysql('SELECT HEX(validationJson) FROM private_problem_version WHERE id='+quote(version))).decode())
    assert proof['runtimeValidated'] and proof['oracleValidated'] and len(proof['combinations'])==6
    assert proof.get('templateId')==expected and row['attemptCount']==1
    row['sixCombinations']=True;row['validationSteps']=int(mysql('SELECT COUNT(*) FROM private_validation_job WHERE taskId='+quote(tid)))
    assert 'referenceSolutions' not in p and 'oracleSolution' not in json.dumps(p)
    report['results'].append(row);save();print(json.dumps(row),flush=True);return True
try:
    api.call('/user/login',{'userAccount':account,'userPassword':password});api.csrf=None
    create('给定小写英文字母字符串，返回首个仅出现一次字符的零起始索引，空串或无解返回-1。','EASY','string_unique')
    create('给定互异正整数硬币数组coins和金额amount，返回凑出金额需要的最少硬币数，每种可无限使用，无解返回-1，金额0返回0。','MEDIUM','dp_coins')
    create('给定整数二叉树，返回可不经过根且不重复经过节点的最大路径和，全负树选非空路径，空树返回0。','HARD','tree_path')
    pending=list(tasks);deadline=time.monotonic()+850
    while pending:
        assert time.monotonic()<deadline,'Real acceptance deadline exceeded'
        waiting=int(mysql("SELECT COUNT(*) FROM ai_task WHERE ownerUserId="+str(uid)+" AND status='RUNNING' AND stage='WAIT_VALIDATION' AND leaseOwner IS NULL"))
        if waiting==3 and not report['allThreeWaitingObserved']:
            report['allThreeWaitingObserved']=True
            report['privateConsumersReleased']=True;save();print(json.dumps({'restartReady':True,'waiting':3}),flush=True)
        for item in pending.copy():
            if collect(item):pending.remove(item)
        time.sleep(1)
    assert report['allThreeWaitingObserved'],'Consumer release was not demonstrated'
    # A directory miss exercises BOTH persisted preflight and full-validation resumptions.
    create('只含小写英文字母的字符串，返回恰好含两种不同字符的最长连续子串长度，允许重复；aabbb返回5，aaaa或空串返回0。不要替换为无重复子串。','MEDIUM',None)
    until=time.monotonic()+850
    while not collect(tasks[-1]):assert time.monotonic()<until;time.sleep(1)
    assert report['results'][-1]['validationSteps']==2 and report['results'][-1]['resumeEvents']==2
    report['passed']=True
finally:
    mysql("UPDATE ai_task SET status='CANCELLED',completedAt=NOW(),leaseOwner=NULL,leaseExpireAt=NULL WHERE ownerUserId="+str(uid)+" AND status IN ('QUEUED','RUNNING')")
    try:api.call('/user/logout',{})
    except Exception:pass
    mysql("START TRANSACTION;UPDATE private_problem SET status='ARCHIVED' WHERE ownerUserId="+str(uid)+";UPDATE user_membership SET validUntil=NOW() WHERE userId="+str(uid)+";UPDATE user SET userRole='ban',isDelete=1 WHERE id="+str(uid)+";COMMIT;")
    pending=int(mysql("SELECT COUNT(*) FROM private_validation_job v JOIN ai_task t ON t.id=v.taskId WHERE t.ownerUserId="+str(uid)+" AND v.status IN ('QUEUED','RUNNING')"))
    if pending==0:mysql("DELETE c FROM private_validation_checkpoint c JOIN private_validation_job v ON v.id=c.validationId JOIN ai_task t ON t.id=v.taskId WHERE t.ownerUserId="+str(uid))
    report['cleanupComplete']=pending==0;save()
print(json.dumps({'passed':report.get('passed',False),'cleanupComplete':report['cleanupComplete']}),flush=True)
