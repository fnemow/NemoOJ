"""Private functional acceptance after capacity test. Synthetic code only."""
import crypt,concurrent.futures,json,secrets,sys,time,uuid
sys.path.insert(0,'/usr/local/lib/nemo-ops')
from common import mysql,atomic_json
from cloud_acceptance import Api,CODES

def main(output):
 clients=[];ids=[];evidence={'started':time.time(),'tryRuns':[],'slowSubmissions':[]}
 try:
  for _ in range(3):
   account='verdict_'+secrets.token_hex(8);password=secrets.token_hex(16);hashed=crypt.crypt(password,crypt.mksalt(crypt.METHOD_BLOWFISH))
   uid=int(mysql("INSERT INTO user(userAccount,userPassword,userName,userRole) VALUES ('"+account+"','"+hashed+"','Verdict acceptance','user'); SELECT LAST_INSERT_ID();"));ids.append(uid)
   api=Api();api.login(account,password);clients.append(api)
  problem=clients[0].call('/question/get/vo/by-number?problemNo=3');version=problem['problemVersion'];before=mysql('SELECT COUNT(*) FROM question_submit;')
  examples=[('java','public class Main { invalid }','Compile Error'),('python','raise ValueError("synthetic runtime failure")','Runtime Error'),('cpp','int main(){for(;;){}}','Time Limit Exceeded'),('python','data=bytearray(1024*1024*1024); print(len(data))','Memory Limit Exceeded'),('python','print("x"*131072)','Output Limit Exceeded')]
  for i,(language,code,expected) in enumerate(examples):
   start=time.monotonic();r=clients[i%3].call('/question/try_run',{'questionId':3,'problemVersion':version,'language':language,'executionMode':'ACM','code':code,'input':''})
   row={'expected':expected,'verdict':r['verdict'],'seconds':round(time.monotonic()-start,3),'diagnosticRedacted':'/app/' not in (r.get('diagnostic') or ''),'diagnosticPresent':bool(r.get('diagnostic')),'resourcesPresent':r.get('executedTime') is not None and r.get('executedMemory') is not None}
   evidence['tryRuns'].append(row);atomic_json(output,evidence);assert row['verdict']==expected and row['diagnosticRedacted']
   if expected in ['Compile Error','Runtime Error']:assert row['diagnosticPresent']
   if expected!='Compile Error':assert row['resourcesPresent']
  numbers=list(range(6000));start=time.monotonic();r=clients[2].call('/question/try_run',{'questionId':3,'problemVersion':version,'language':'cpp','executionMode':'FUNCTION','code':CODES['cpp'],'input':json.dumps({'nums':numbers,'target':5999},separators=(',',':'))})
  assert r['verdict']=='Completed' and r['output'].strip()=='5999'
  evidence['largeInput']={'integers':6000,'verdict':r['verdict'],'seconds':round(time.monotonic()-start,3),'outputMatches':True}
  assert before==mysql('SELECT COUNT(*) FROM question_submit;');evidence['tryRunStatsUnchanged']=True
  slow_java='class Solution {public int search(int[] nums,int target){long end=System.nanoTime()+600000000L;while(System.nanoTime()<end){} int l=0,r=nums.length-1;while(l<=r){int m=l+(r-l)/2;if(nums[m]==target)return m;if(nums[m]<target)l=m+1;else r=m-1;}return -1;}}'
  slow_cpp='#include <vector>\n#include <chrono>\nusing namespace std;class Solution {public:int search(vector<int>& nums,int target){auto end=chrono::steady_clock::now()+chrono::milliseconds(600);while(chrono::steady_clock::now()<end){}int l=0,r=(int)nums.size()-1;while(l<=r){int m=l+(r-l)/2;if(nums[m]==target)return m;if(nums[m]<target)l=m+1;else r=m-1;}return -1;}};'
  def submit(i,language,code):
   api=clients[i];start=time.monotonic();sid=str(api.call('/question/question_submit/do',{'questionId':3,'problemVersion':version,'language':language,'executionMode':'FUNCTION','code':code,'clientRequestId':str(uuid.uuid4())}));end=time.monotonic()+180
   while time.monotonic()<end:
    r=api.call('/question/question_submit/get/vo?id='+sid)
    if r['status'] in [2,3]:break
    time.sleep(.5)
   return {'id':sid,'language':language,'verdict':r.get('verdict'),'seconds':round(time.monotonic()-start,3),'attemptCount':int(mysql('SELECT attemptCount FROM question_submit WHERE id='+sid+';'))}
  with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
   futures=[pool.submit(submit,0,'java',slow_java),pool.submit(submit,1,'cpp',slow_cpp)]
   evidence['slowSubmissions']=[f.result() for f in futures]
  assert all(r['verdict']=='accepted' and r['attemptCount']==1 for r in evidence['slowSubmissions'])
  evidence['passed']=True
 finally:
  evidence['ended']=time.time()
  if ids:
   joined=','.join(map(str,ids));pending=int(mysql('SELECT COUNT(*) FROM question_submit WHERE userId IN ('+joined+') AND status IN(0,1) AND isDelete=0;'))
   if pending==0:mysql("UPDATE question_submit SET isDelete=1 WHERE userId IN ("+joined+"); UPDATE user SET userRole='ban',isDelete=1 WHERE id IN ("+joined+");")
   evidence['fixturesRemoved']=pending==0
  atomic_json(output,evidence)
 print(json.dumps(evidence))
if __name__=='__main__':main(sys.argv[1])
