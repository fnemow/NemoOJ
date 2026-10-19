"""Bounded real-provider acceptance. All credentials/sessions remain in host memory."""
import crypt
import json
import secrets
import sys
import time
import uuid
from pathlib import Path
sys.path.insert(0,'/usr/local/lib/nemo-ops')
sys.path.insert(0,'/home/ubuntu/nemo-catalog-306-20261011')
from common import mysql as raw_mysql
from catalog_306_release import Api

def mysql(sql):return raw_mysql('SET NAMES utf8mb4;\n'+sql)
def quote(s):return "'"+str(s).replace("'","''")+"'"

def main(output):
    account='staged_'+secrets.token_hex(6); password=secrets.token_hex(16)
    hashed=crypt.crypt(password,crypt.mksalt(crypt.METHOD_BLOWFISH))
    uid=int(mysql("INSERT INTO user(userAccount,userPassword,userName,userRole) VALUES ("+quote(account)+','+quote(hashed)+",'阶段生成验收','user');SELECT LAST_INSERT_ID();"))
    mysql("INSERT INTO user_membership(userId,tier,validFrom,validUntil,updatedBy) VALUES ("+str(uid)+",'VIP',NOW(),DATE_ADD(NOW(),INTERVAL 1 DAY),'staged-acceptance')")
    api=Api(); report={'results':[],'cleanupComplete':False}
    try:
        api.call('/user/login',{'userAccount':account,'userPassword':password});api.csrf=None
        for goal,difficulty in [
            ('给定只含小写英文字母的字符串，返回第一个只出现一次的字符的零起始下标，不存在返回-1。练习字符串和哈希计数。','EASY'),
            ('给定小写英文字母字符串，返回不含重复字符的最长连续子串的长度。练习滑动窗口，空字符串返回0。','MEDIUM')]:
            if '--medium' in sys.argv and difficulty!='MEDIUM':continue
            start=time.monotonic()
            task=api.call('/question/ai/private-problems',{'goal':goal,'difficulty':difficulty,'tags':['字符串'],'idempotencyKey':str(uuid.uuid4())})
            tid=task['taskId']; deadline=time.monotonic()+930
            while True:
                status=api.call('/question/ai/tasks/'+tid)
                if status['status'] in ('SUCCEEDED','FAILED','CANCELLED'):break
                assert time.monotonic()<deadline,'Generation acceptance deadline exceeded'
                time.sleep(2)
            row={'difficulty':difficulty,'status':status['status'],'errorCode':status.get('errorCode'),'seconds':round(time.monotonic()-start,3)}
            usage=mysql("SELECT COUNT(*),COALESCE(SUM(inputTokens),0),COALESCE(SUM(outputTokens),0) FROM ai_model_call WHERE taskId="+quote(tid)).split('\t')
            row.update(modelCalls=int(usage[0]),inputTokens=int(usage[1]),outputTokens=int(usage[2]))
            rows=mysql("SELECT COALESCE(JSON_UNQUOTE(JSON_EXTRACT(resultJson,'$.category')),'NONE') FROM private_validation_job WHERE taskId="+quote(tid)+" ORDER BY revision")
            row['validationCategories']=rows.splitlines()
            if status['status']=='SUCCEEDED':
                p=api.call('/question/ai/private-problems/'+task['privateProblemId'])
                version=p['currentVersion']['id']
                proof=json.loads(bytes.fromhex(mysql('SELECT HEX(validationJson) FROM private_problem_version WHERE id='+quote(version))).decode())
                assert proof['oracleValidated'] and proof['runtimeValidated'] and len(proof['combinations'])==6
                row['sixCombinations']=True
                assert 'referenceSolutions' not in p and 'oracleSolution' not in json.dumps(p)
            report['results'].append(row)
            Path(output).write_text(json.dumps(report,ensure_ascii=False,indent=2))
            print(json.dumps(row),flush=True)
        report['passed']=all(r['status']=='SUCCEEDED' for r in report['results'])
    finally:
        active=int(mysql("SELECT COUNT(*) FROM ai_task WHERE ownerUserId="+str(uid)+" AND status IN ('QUEUED','RUNNING')"))
        if active:
            mysql("UPDATE ai_task SET status='CANCELLED',completedAt=NOW(),leaseOwner=NULL,leaseExpireAt=NULL WHERE ownerUserId="+str(uid)+" AND status IN ('QUEUED','RUNNING')")
        try:api.call('/user/logout',{})
        except Exception:pass
        mysql("START TRANSACTION;UPDATE private_problem SET status='ARCHIVED' WHERE ownerUserId="+str(uid)+";UPDATE user_membership SET validUntil=NOW() WHERE userId="+str(uid)+";UPDATE user SET userRole='ban',isDelete=1 WHERE id="+str(uid)+";COMMIT;")
        report['cleanupComplete']=True
        Path(output).write_text(json.dumps(report,ensure_ascii=False,indent=2))
    print(json.dumps({'passed':report.get('passed',False),'cleanupComplete':True}),flush=True)

if __name__=='__main__':main(sys.argv[1])
