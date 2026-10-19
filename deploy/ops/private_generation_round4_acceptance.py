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
    account='eval4_'+secrets.token_hex(6); password=secrets.token_hex(16)
    hashed=crypt.crypt(password,crypt.mksalt(crypt.METHOD_BLOWFISH))
    uid=int(mysql("INSERT INTO user(userAccount,userPassword,userName,userRole) VALUES ("+quote(account)+','+quote(hashed)+",'第四轮生成验收','user');SELECT LAST_INSERT_ID();"))
    mysql("INSERT INTO user_membership(userId,tier,validFrom,validUntil,updatedBy) VALUES ("+str(uid)+",'VIP',NOW(),DATE_ADD(NOW(),INTERVAL 1 DAY),'staged-acceptance')")
    api=Api(); report={'results':[],'cleanupComplete':False}
    try:
        api.call('/user/login',{'userAccount':account,'userPassword':password});api.csrf=None
        cases=[
            ('给定小写英文字母字符串，返回首个仅出现一次字符的零起始索引，空串或无解返回-1。','EASY','string_unique'),
            ('给定小写英文字母字符串s和t，返回覆盖t全部字符及重复次数的最短子串，等长选最早，t为空或无解返回空串。','HARD','string_window'),
            ('给定无环整数单链表head和k，原地每k个节点反转，尾部不足k个保持，返回反转后的节点值数组，除返回数组外O(1)空间。','HARD','list_groups'),
            ('给定整数二叉树，返回可不经过根且不重复经过节点的最大路径和，全负树选非空路径，空树返回0。','HARD','tree_path'),
            ('只含小写英文字母的字符串，返回恰好含两种不同字符的最长连续子串长度，允许重复；aabbb返回5，aaaa或空串返回0。不要替换为无重复子串。','MEDIUM',None)]
        for goal,difficulty,expected_template in cases:
            start=time.monotonic()
            task=api.call('/question/ai/private-problems',{'goal':goal,'difficulty':difficulty,'tags':['链表' if expected_template and expected_template.startswith('list_') else '二叉树' if expected_template and expected_template.startswith('tree_') else '字符串'],'idempotencyKey':str(uuid.uuid4())})
            tid=task['taskId']; deadline=time.monotonic()+930
            while True:
                status=api.call('/question/ai/tasks/'+tid)
                if status['status'] in ('SUCCEEDED','FAILED','CANCELLED'):break
                assert time.monotonic()<deadline,'Generation acceptance deadline exceeded'
                time.sleep(2)
            row={'expectedTemplate':expected_template,'difficulty':difficulty,'status':status['status'],'errorCode':status.get('errorCode'),'seconds':round(time.monotonic()-start,3)}
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
                row['actualTemplate']=proof.get('templateId')
                row['templateSelectionCorrect']=row['actualTemplate']==expected_template
                row['repairCount']=int(mysql('SELECT generationRepairCount FROM ai_task WHERE id='+quote(tid)))
                row['firstPass']=row['repairCount']==0 and not mysql("SELECT id FROM private_validation_job WHERE taskId="+quote(tid)+" AND status='FAILED'")
                row['validationSteps']=int(mysql('SELECT COUNT(*) FROM private_validation_job WHERE taskId='+quote(tid)))
                assert 'referenceSolutions' not in p and 'oracleSolution' not in json.dumps(p)
            report['results'].append(row)
            Path(output).write_text(json.dumps(report,ensure_ascii=False,indent=2))
            print(json.dumps(row),flush=True)
        report['passed']=all(r['status']=='SUCCEEDED' and r.get('templateSelectionCorrect') for r in report['results'])
    finally:
        active=int(mysql("SELECT COUNT(*) FROM ai_task WHERE ownerUserId="+str(uid)+" AND status IN ('QUEUED','RUNNING')"))
        if active:
            mysql("UPDATE ai_task SET status='CANCELLED',completedAt=NOW(),leaseOwner=NULL,leaseExpireAt=NULL WHERE ownerUserId="+str(uid)+" AND status IN ('QUEUED','RUNNING')")
        try:api.call('/user/logout',{})
        except Exception:pass
        mysql("START TRANSACTION;UPDATE private_problem SET status='ARCHIVED' WHERE ownerUserId="+str(uid)+";UPDATE user_membership SET validUntil=NOW() WHERE userId="+str(uid)+";UPDATE user SET userRole='ban',isDelete=1 WHERE id="+str(uid)+";COMMIT;")
        pending=int(mysql("SELECT COUNT(*) FROM private_validation_job v JOIN ai_task t ON t.id=v.taskId WHERE t.ownerUserId="+str(uid)+" AND v.status IN ('QUEUED','RUNNING')"))
        if pending==0:
            mysql("DELETE c FROM private_validation_checkpoint c JOIN private_validation_job v ON v.id=c.validationId JOIN ai_task t ON t.id=v.taskId WHERE t.ownerUserId="+str(uid))
        report['cleanupComplete']=pending==0
        Path(output).write_text(json.dumps(report,ensure_ascii=False,indent=2))
    print(json.dumps({'passed':report.get('passed',False),'cleanupComplete':report.get('cleanupComplete',False)}),flush=True)

if __name__=='__main__':main(sys.argv[1])
