"""Small normal-user regression while background generation may be active."""
import crypt,json,secrets,sys,time,uuid
from pathlib import Path
sys.path.insert(0,'/usr/local/lib/nemo-ops');from common import mysql,atomic_json
sys.path.insert(0,'/home/ubuntu/nemo-catalog-306-20261011');from catalog_306_release import Api
stage=Path(sys.argv[1]);account='formal6_'+secrets.token_hex(6);password=secrets.token_hex(16)
def quote(v):return "'"+str(v).replace("'","''")+"'"
hashed=crypt.crypt(password,crypt.mksalt(crypt.METHOD_BLOWFISH))
uid=int(mysql("INSERT INTO user(userAccount,userPassword,userName,userRole) VALUES ("+quote(account)+','+quote(hashed)+",'Round6 formal acceptance','user');SELECT LAST_INSERT_ID();"))
sources={
 'java':'class Solution {public int search(int[] nums,int target){int l=0,r=nums.length-1;while(l<=r){int m=(l+r)>>>1;if(nums[m]==target)return m;if(nums[m]<target)l=m+1;else r=m-1;}return -1;}}',
 'python':'class Solution:\n def search(self,nums,target):\n  l=0;r=len(nums)-1\n  while l<=r:\n   m=(l+r)//2\n   if nums[m]==target:return m\n   if nums[m]<target:l=m+1\n   else:r=m-1\n  return -1',
 'cpp':'class Solution{public:int search(vector<int>& nums,int target){int l=0,r=(int)nums.size()-1;while(l<=r){int m=(l+r)/2;if(nums[m]==target)return m;if(nums[m]<target)l=m+1;else r=m-1;}return -1;}};'}
cases=[(lang,source,'accepted') for lang,source in sources.items()]+[('java','class Solution { invalid };','compile_error'),('python','class Solution:\n def search(self,nums,target):raise RuntimeError("authored regression")','runtime_error')]
api=Api();r={'results':[]}
try:
 api.call('/user/login',{'userAccount':account,'userPassword':password});api.csrf=None
 p=api.call('/question/get/vo/by-number?problemNo=3')
 for language,source,expected in cases:
  start=time.monotonic();sid=api.call('/question/question_submit/do',{'questionId':p['id'],'problemVersion':p['problemVersion'],'language':language,'executionMode':'FUNCTION','code':source,'clientRequestId':str(uuid.uuid4())})
  end=time.monotonic()+90
  while time.monotonic()<end:
   value=api.call('/question/question_submit/get/vo?id='+str(sid))
   if value['status'] in (2,3):break
   time.sleep(.5)
  else:raise RuntimeError('Formal test deadline exceeded')
  info=value.get('judgeInfo') or {};verdict=value.get('verdict') or info.get('message');assert verdict==expected
  if expected=='accepted':assert info.get('executedTime') is not None and info.get('executedMemory') is not None
  attempts=int(mysql('SELECT attemptCount FROM question_submit WHERE id='+str(sid)));assert attempts==1
  r['results'].append({'language':language,'verdict':verdict,'seconds':round(time.monotonic()-start,3),'attemptCount':attempts,'resourcesPresent':info.get('executedMemory') is not None});atomic_json(stage/'formal.json',r)
 r['passed']=True
finally:
 assert mysql('SELECT COUNT(*) FROM question_submit WHERE userId='+str(uid)+' AND status IN (0,1)')=='0','Test submissions still active'
 try:api.call('/user/logout',{})
 except Exception:pass
 mysql("START TRANSACTION;UPDATE question_submit SET isDelete=1 WHERE userId="+str(uid)+";UPDATE user SET userRole='ban',isDelete=1 WHERE id="+str(uid)+";COMMIT;")
 r['cleanupComplete']=True;atomic_json(stage/'formal.json',r)
print(json.dumps(r),flush=True)
