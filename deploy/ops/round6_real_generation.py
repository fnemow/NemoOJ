"""One normal VIP request through the live model/Agent; credentials stay in host memory."""
import crypt,json,secrets,sys,time,uuid
from pathlib import Path
sys.path.insert(0,'/usr/local/lib/nemo-ops');from common import mysql as raw_mysql,atomic_json
sys.path.insert(0,'/home/ubuntu/nemo-catalog-306-20261011');from catalog_306_release import Api
stage=Path(sys.argv[1]);account='eval6_'+secrets.token_hex(6);password=secrets.token_hex(16)
def mysql(sql):return raw_mysql('SET NAMES utf8mb4;\n'+sql)
def quote(v):return "'"+str(v).replace("'","''")+"'"
hashed=crypt.crypt(password,crypt.mksalt(crypt.METHOD_BLOWFISH))
uid=int(mysql("INSERT INTO user(userAccount,userPassword,userName,userRole) VALUES ("+quote(account)+','+quote(hashed)+",'第六轮生成验收','user');SELECT LAST_INSERT_ID();"))
mysql("INSERT INTO user_membership(userId,tier,validFrom,validUntil,updatedBy) VALUES ("+str(uid)+",'VIP',NOW(),DATE_ADD(NOW(),INTERVAL 1 DAY),'round6-acceptance')")
api=Api();r={}
try:
 api.call('/user/login',{'userAccount':account,'userPassword':password});api.csrf=None
 start=time.monotonic();job=api.call('/question/ai/private-problems',{'goal':'给定非空整数矩阵matrix和多组queries，每项是[top,left,bottom,right]，返回闭区间矩形元素和数组，按查询顺序。矩阵没有修改，练习二维前缀和。','difficulty':'HARD','idempotencyKey':str(uuid.uuid4())})
 task=job['taskId'];deadline=time.monotonic()+600
 while time.monotonic()<deadline:
  status=api.call('/question/ai/tasks/'+task)
  if status['status'] in ('SUCCEEDED','FAILED','CANCELLED'):break
  time.sleep(1)
 else:raise RuntimeError('Real generation deadline exceeded')
 assert status['status']=='SUCCEEDED','Real generation did not succeed'
 item=api.call('/question/ai/private-problems/'+job['privateProblemId']);version=item['currentVersion']['id']
 proof=json.loads(bytes.fromhex(mysql('SELECT HEX(validationJson) FROM private_problem_version WHERE id='+quote(version))).decode())
 assert proof['runtimeValidated'] and proof['oracleValidated'] and len(proof['combinations'])==6 and proof['templateId']=='prefix_matrix'
 usage=mysql('SELECT COUNT(*),COALESCE(SUM(inputTokens),0),COALESCE(SUM(outputTokens),0) FROM ai_model_call WHERE taskId='+quote(task)).split('\t')
 r={'passed':True,'seconds':round(time.monotonic()-start,3),'modelCalls':int(usage[0]),'inputTokens':int(usage[1]),'outputTokens':int(usage[2]),'templateId':proof['templateId'],'sixCombinations':True,'attemptCount':int(mysql('SELECT attemptCount FROM ai_task WHERE id='+quote(task)))}
 assert r['attemptCount']==1 and 'referenceSolutions' not in item
finally:
 pending=int(mysql('SELECT COUNT(*) FROM ai_task WHERE ownerUserId='+str(uid)+" AND status IN ('QUEUED','RUNNING')"))
 assert pending==0,'Real task remains active; defer cleanup'
 try:api.call('/user/logout',{})
 except Exception:pass
 mysql("START TRANSACTION;UPDATE private_problem SET status='ARCHIVED' WHERE ownerUserId="+str(uid)+";UPDATE user_membership SET validUntil=NOW() WHERE userId="+str(uid)+";UPDATE user SET userRole='ban',isDelete=1 WHERE id="+str(uid)+";DELETE c FROM private_validation_checkpoint c JOIN private_validation_job v ON v.id=c.validationId JOIN ai_task t ON t.id=v.taskId WHERE t.ownerUserId="+str(uid)+";COMMIT;")
 r['cleanupComplete']=True;atomic_json(stage/'real-generation.json',r)
print(json.dumps(r),flush=True)
