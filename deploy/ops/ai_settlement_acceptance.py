"""Verify real signed failure settlement without calling a model or sending mail."""
import sys,json,uuid,secrets,time,hmac,hashlib,urllib.request,subprocess
sys.path.insert(0,'/usr/local/lib/nemo-ops')
from common import mysql,env_file,container,atomic_json

def main(output):
 env=env_file('/srv/nemo-oj/deploy/.env');uid=int(mysql("INSERT INTO user(userAccount,userPassword,userName,userRole) VALUES ('budget_"+secrets.token_hex(8)+"','disabled-test-password','Budget settlement test','ban'); SELECT LAST_INSERT_ID();"))
 c=json.loads(subprocess.check_output(['docker','inspect',container('core','question-service')]))[0];ip=next(v['IPAddress'] for v in c['NetworkSettings']['Networks'].values() if v['IPAddress']);results=[];tasks=[]
 try:
  for code,expected in [('MODEL_TIMEOUT','0.02000000'),('MODEL_ERROR','0.02000000'),('CONTEXT_UNAVAILABLE','0.00000000')]:
   tid=str(uuid.uuid4());tasks.append(tid)
   mysql("INSERT INTO ai_task(id,type,ownerUserId,resourceId,idempotencyKey,status,attemptCount,leaseExpireAt) VALUES ('"+tid+"','ANALYSIS',"+str(uid)+",'0','"+tid+"','RUNNING',999,DATE_ADD(NOW(),INTERVAL 5 MINUTE)); INSERT INTO ai_usage(taskId,userId,usageDate,category,state,quotaExempt,reservedCost) VALUES ('"+tid+"',"+str(uid)+",CURRENT_DATE(),'ANALYSIS','RESERVED',0,0.02);")
   path='/api/question/internal/ai/tasks/'+tid+'/fail';body=json.dumps({'errorCode':code},separators=(',',':')).encode();timestamp=str(int(time.time()));msg=(timestamp+'\nPOST\n'+path+'\n'+hashlib.sha256(body).hexdigest()).encode();signature=hmac.new(env['AI_INTERNAL_SECRET'].encode(),msg,hashlib.sha256).hexdigest()
   request=urllib.request.Request('http://'+ip+':8103'+path,data=body,headers={'Content-Type':'application/json','X-Agent-Timestamp':timestamp,'X-Agent-Signature':signature})
   with urllib.request.urlopen(request,timeout=30) as response:result=json.load(response)
   assert result['code']==0 and result['data'] is True
   state=mysql("SELECT state,reservedCost FROM ai_usage WHERE taskId='"+tid+"';")
   assert state=='RELEASED\t'+expected,state
   results.append({'fault':code,'state':'RELEASED','retainedBudget':expected})
  quota=int(mysql("SELECT COUNT(*) FROM ai_usage WHERE userId="+str(uid)+" AND state IN('RESERVED','COMMITTED') AND quotaExempt=0;"));assert quota==0
  budget=mysql("SELECT SUM(GREATEST(estimatedCost,reservedCost)) FROM ai_usage WHERE userId="+str(uid)+";");assert float(budget)==0.04
  atomic_json(output,{'results':results,'userQuotaUsed':quota,'uncertainBudgetCounted':budget,'modelCalls':0,'emails':0})
 finally:
  for tid in tasks:mysql("DELETE FROM ai_usage WHERE taskId='"+tid+"'; DELETE FROM ai_task WHERE id='"+tid+"' AND ownerUserId="+str(uid)+";")
  mysql('UPDATE user SET isDelete=1 WHERE id='+str(uid)+';')
if __name__=='__main__':main(sys.argv[1])
