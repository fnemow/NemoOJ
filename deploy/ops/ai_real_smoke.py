"""One authorized real-model smoke test over the private API. No secrets in evidence."""
import crypt,json,secrets,sys,time,uuid
from pathlib import Path
sys.path.insert(0,'/usr/local/lib/nemo-ops')
from common import mysql,atomic_json
from cloud_acceptance import Api,CODES

def main(output):
 account='ai_smoke_'+secrets.token_hex(8);password=secrets.token_hex(16);hashed=crypt.crypt(password,crypt.mksalt(crypt.METHOD_BLOWFISH))
 uid=int(mysql("INSERT INTO user(userAccount,userPassword,userName,userRole) VALUES ('"+account+"','"+hashed+"','AI smoke acceptance','user'); SELECT LAST_INSERT_ID();"));api=Api();result={'started':time.time(),'testUserId':uid};sid=None;tid=None
 try:
  api.login(account,password)
  enabled=api.call('/question/ai/entitlements');assert enabled['analysisEnabled']
  problem=api.call('/question/get/vo/by-number?problemNo=3')
  sid=str(api.call('/question/question_submit/do',{'questionId':problem['id'],'problemVersion':problem['problemVersion'],'language':'python','executionMode':'FUNCTION','code':CODES['python'],'clientRequestId':str(uuid.uuid4())}))
  end=time.monotonic()+180
  while time.monotonic()<end:
   detail=api.call('/question/question_submit/get/vo?id='+sid)
   if detail['status'] in [2,3]:break
   time.sleep(.5)
  assert detail.get('verdict')=='accepted'
  before=api.call('/question/ai/entitlements')['remainingToday'];start=time.monotonic()
  created=api.call('/question/ai/analyses',{'submissionId':sid,'idempotencyKey':str(uuid.uuid4())});tid=created['taskId']
  end=time.monotonic()+240
  while time.monotonic()<end:
   task=api.call('/question/ai/tasks/'+tid)
   if task['status'] in ['SUCCEEDED','FAILED','CANCELLED']:break
   time.sleep(1)
  assert task['status']=='SUCCEEDED',task.get('errorCode')
  report=api.call('/question/ai/analyses/'+sid);after=api.call('/question/ai/entitlements')['remainingToday']
  cached=api.call('/question/ai/analyses',{'submissionId':sid,'idempotencyKey':str(uuid.uuid4())})
  assert cached.get('reused') is True and cached.get('status')=='SUCCEEDED'
  assert api.call('/question/ai/entitlements')['remainingToday']==after==before-1
  assert int(mysql('SELECT COUNT(*) FROM ai_task WHERE ownerUserId='+str(uid)+';'))==1
  state=mysql("SELECT state,reservedCost,inputTokens,outputTokens,estimatedCost FROM ai_usage WHERE taskId='"+tid+"';").split('\t')
  assert state[0]=='COMMITTED' and float(state[1])==0
  calls=mysql("SELECT state,inputTokens,outputTokens,estimatedCost,reservedCost FROM ai_model_call WHERE taskId='"+tid+"';").splitlines()
  assert len(calls)==1
  receipt=calls[0].split('\t')
  assert receipt[0]=='KNOWN' and int(receipt[1])==int(state[2]) and int(receipt[2])==int(state[3])
  assert float(receipt[3])==float(state[4]) and float(receipt[4])==0
  result.update(passed=True,submissionId=sid,taskId=tid,analysisSeconds=round(time.monotonic()-start,3),modelId=report['modelId'],reportFields=sorted(report['report']),suggestions=len(report['report'].get('suggestions',[])),remainingBefore=before,remainingAfter=after,cacheReuse=True,knownModelCalls=1,usage={'state':state[0],'inputTokens':int(state[2]),'outputTokens':int(state[3]),'estimatedCostUsd':state[4]})
 finally:
  result['ended']=time.time()
  pending=int(mysql('SELECT COUNT(*) FROM question_submit WHERE userId='+str(uid)+' AND status IN(0,1) AND isDelete=0;'))
  ai_pending=int(mysql("SELECT COUNT(*) FROM ai_task WHERE ownerUserId="+str(uid)+" AND status IN('QUEUED','RUNNING');"))
  if pending==0 and ai_pending==0:
   mysql('UPDATE question_submit SET isDelete=1 WHERE userId='+str(uid)+"; UPDATE user SET userRole='ban',isDelete=1 WHERE id="+str(uid)+';');result['fixturesRemoved']=True
  else:result['fixturesRemoved']=False
  # Preserve the real provider cost ledger; do not erase test spending.
  result['usageLedgerPreserved']=True;atomic_json(output,result)
 print(json.dumps({k:v for k,v in result.items() if k not in ['submissionId','taskId','reportFields']}))
if __name__=='__main__':main(sys.argv[1])
