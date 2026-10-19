"""One bounded real generation task and three formal private submissions."""
import crypt,json,secrets,sys,time,uuid
from pathlib import Path
sys.path.insert(0,'/usr/local/lib/nemo-ops');from common import mysql as raw_mysql,atomic_json
sys.path.insert(0,'/home/ubuntu/nemo-catalog-306-20261011');from catalog_306_release import Api
stage=Path(sys.argv[1]);report={'passed':False,'privateSubmissions':[]};accounts=[];apis=[]
def mysql(sql):return raw_mysql('SET NAMES utf8mb4;\n'+sql)
def quote(v):return "'"+str(v).replace("'","''")+"'"
try:
    for i in range(2):
        account='nodes_eval_'+secrets.token_hex(5);password=secrets.token_hex(16);hashed=crypt.crypt(password,crypt.mksalt(crypt.METHOD_BLOWFISH))
        uid=int(mysql('INSERT INTO user(userAccount,userPassword,userName,userRole) VALUES ('+quote(account)+','+quote(hashed)+",'自定义节点验收','user');SELECT LAST_INSERT_ID();"));accounts.append(uid)
        mysql("INSERT INTO user_membership(userId,tier,validFrom,validUntil,updatedBy) VALUES ("+str(uid)+",'VIP',NOW(),DATE_ADD(NOW(),INTERVAL 1 DAY),'node-acceptance')")
        api=Api();api.call('/user/login',{'userAccount':account,'userPassword':password});api.csrf=None;apis.append(api)
    goal='学习带父指针和共享引用的加权图遍历，必须以单个 node<ColoredVertex> start 为核心代码参数，返回从 start 沿 parent 和 links 可达的所有节点的 value 之和，每节点仅计一次，空节点返回0。请在 signature.dataStructures 声明 ColoredVertex，字段按 value:int、parent:ref、links:ref[] 顺序，不用基础数组代替节点参数，不在 Solution 内重定义节点类。图可有环、共享引用，节点值小整数，20组小输入每组最多5节点，所有节点从根沿声明引用可达。优化用带访问集合的遍历，独立校验用重复扩张集合直到稳定。'
    api=apis[0];start=time.monotonic()
    if '--reuse' in sys.argv:
        row=mysql("SELECT t.id,t.resourceId FROM ai_task t JOIN user u ON u.id=t.ownerUserId WHERE u.userAccount LIKE 'nodes_eval_%' AND t.status='SUCCEEDED' AND t.type='PRIVATE_PROBLEM' ORDER BY t.createTime DESC LIMIT 1").split('\t')
        assert len(row)==2;task,pid=row
        mysql('UPDATE private_problem SET ownerUserId='+str(accounts[0])+",status='READY' WHERE id="+quote(pid)+" AND status='ARCHIVED' AND ownerUserId IN (SELECT id FROM user WHERE userAccount LIKE 'nodes_eval_%')")
        job={'taskId':task,'privateProblemId':pid};status={'status':'SUCCEEDED'};report.update(reusedGeneratedFixture=True,additionalModelCalls=0)
    else:
        job=api.call('/question/ai/private-problems',{'goal':goal,'difficulty':'MEDIUM','idempotencyKey':str(uuid.uuid4())});task=job['taskId'];deadline=time.monotonic()+420
        while time.monotonic()<deadline:
            status=api.call('/question/ai/tasks/'+task)
            if status['status'] in ('SUCCEEDED','FAILED','CANCELLED'):break
            time.sleep(.5)
    seconds=round(time.monotonic()-start,3) if '--reuse' not in sys.argv else float(mysql('SELECT TIMESTAMPDIFF(MICROSECOND,createTime,completedAt)/1000000 FROM ai_task WHERE id='+quote(task)))
    report.update(generationSeconds=seconds,status=status['status'],modelCalls=int(mysql('SELECT COUNT(*) FROM ai_model_call WHERE taskId='+quote(task))),attemptCount=int(mysql('SELECT attemptCount FROM ai_task WHERE id='+quote(task))))
    atomic_json(stage/'live.json',report);print(json.dumps(report),flush=True)
    assert status['status']=='SUCCEEDED','Custom node generation did not succeed'
    item=api.call('/question/ai/private-problems/'+job['privateProblemId']);v=item['currentVersion'];version=v['id'];spec=v['problemSpec']
    assert spec['parameters'][0]['type']=='node<ColoredVertex>' and spec['dataStructures'][0]['name']=='ColoredVertex'
    assert len(v['functionSamples'])==2 and spec.get('judgeCases') is None
    assert 'referenceSolutions' not in item and 'referenceJson' not in v
    assert set(v['availableModes'])=={'ACM','FUNCTION'}
    assert {t['language'] for t in v['templates'] if t['mode']=='FUNCTION'}=={'java','python','cpp'}
    for template in v['templates']:
        if template['mode']=='FUNCTION':assert 'ColoredVertex' in template['code'] or template['language']=='python'
    proof=json.loads(bytes.fromhex(mysql('SELECT HEX(validationJson) FROM private_problem_version WHERE id='+quote(version))).decode())
    assert proof['runtimeValidated'] and proof['oracleValidated'] and len(proof['combinations'])==6 and not proof.get('completeProofReused')
    references=json.loads(bytes.fromhex(mysql('SELECT HEX(referenceJson) FROM private_problem_version WHERE id='+quote(version))).decode())
    report.update(customClass='ColoredVertex',sixCombinationsPassed=True,independentOraclePassed=True,fullProofExecuted=True,publicFunctionSamples=2)
    try:apis[1].call('/question/ai/private-problems/'+job['privateProblemId'])
    except RuntimeError as error:assert str(error).startswith('API rejected ');report['foreignReadRejected']=True
    else:raise AssertionError('Foreign read accepted')
    for language in ('java','python','cpp'):
        submitted=time.monotonic();sid=api.call('/question/ai/private-problems/'+job['privateProblemId']+'/submissions',{'problemVersion':version,'language':language,'executionMode':'FUNCTION','code':references[language+':FUNCTION'],'clientRequestId':str(uuid.uuid4())})
        until=time.monotonic()+120
        while time.monotonic()<until:
            value=api.call('/question/question_submit/get/vo?id='+str(sid))
            if value['status'] in (2,3):break
            time.sleep(.5)
        info=value.get('judgeInfo') or {};assert (value.get('verdict') or info.get('message'))=='accepted'
        assert info.get('executedTime') is not None and info.get('executedMemory') is not None
        report['privateSubmissions'].append({'language':language,'verdict':'accepted','resourcesPresent':True,'seconds':round(time.monotonic()-submitted,3)})
        atomic_json(stage/'live.json',report)
    report['passed']=True
finally:
    ids=','.join(str(u) for u in accounts)
    pending=(int(mysql("SELECT COUNT(*) FROM ai_task WHERE ownerUserId IN ("+ids+") AND status IN ('QUEUED','RUNNING')"))+int(mysql('SELECT COUNT(*) FROM question_submit WHERE userId IN ('+ids+') AND status IN (0,1)'))) if ids else 0
    if not pending:
        for api in apis:
            try:api.call('/user/logout',{})
            except Exception:pass
        if ids:mysql("START TRANSACTION;UPDATE question_submit SET isDelete=1 WHERE userId IN ("+ids+");UPDATE private_problem SET status='ARCHIVED' WHERE ownerUserId IN ("+ids+");UPDATE user_membership SET validUntil=NOW() WHERE userId IN ("+ids+");UPDATE user SET userRole='ban',isDelete=1 WHERE id IN ("+ids+");COMMIT;")
    report['cleanupComplete']=not pending;atomic_json(stage/'live.json',report)
print(json.dumps(report),flush=True)
