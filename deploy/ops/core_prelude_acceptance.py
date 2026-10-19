"""Public API acceptance with an ephemeral user; no user code or secrets in evidence."""
import crypt
import json
import secrets
import sys
import time
import uuid
from pathlib import Path
from catalog_306_release import Api, old_fingerprint
sys.path.insert(0, '/usr/local/lib/nemo-ops')
from common import mysql, atomic_json

JAVA = '''class Solution {
 public int search(int[] nums,int target) {
  List<Integer> xs=new ArrayList<>(); for(int x:nums)xs.add(x);
  Deque<Integer> q=new ArrayDeque<>(xs); Map<Integer,Integer> m=new HashMap<>();
  BigInteger b=BigInteger.valueOf(6).gcd(BigInteger.valueOf(4));
  Pattern p=Pattern.compile("x"); if(b.intValue()!=2||!p.matcher("x").matches())throw new AssertionError();
  int i=Arrays.binarySearch(nums,target); return i<0?-1:i;
 }
}'''
PYTHON = '''class Solution:
 def search(self,nums: List[int],target: int) -> int:
  q=deque(nums); counts=Counter(q); node: Optional[ListNode]=None
  assert gcd(6,4)==2 and math.isqrt(9)==3
  i=bisect_left(nums,target)
  return i if i<len(nums) and nums[i]==target else -1
'''
CPP = '''class Solution {public: int search(vector<int>& nums,int target) {
 unordered_map<int,int> m; stack<int> s; priority_queue<int,vector<int>,greater<int>> q;
 function<int(int)> f=[](int x){return x;}; assert(gcd(6,4)==2);
 auto it=lower_bound(nums.begin(),nums.end(),target); return it!=nums.end()&&*it==target?int(it-nums.begin()):-1;
}};'''


def main(stage):
    jobs = [
        ('java', JAVA, 'accepted', None), ('python', PYTHON, 'accepted', None), ('cpp', CPP, 'accepted', None),
        ('java', 'class Solution {public int search(int[] nums,int target){return missingName;}}', 'compile_error', 'Solution.java:1:'),
        ('java', 'class Solution {\n public int search(int[] nums,int target){\n return missingName;\n }\n}', 'compile_error', 'Solution.java:3:'),
        ('java', 'class Solution {\n public int search(int[] nums,int target){\n throw new IllegalStateException("expected");\n }\n}', 'runtime_error', 'Solution.java:3'),
        ('python', "class Solution:\n def search(self,nums,target):\n  raise ValueError('expected')\n", 'runtime_error', 'line 3'),
    ]
    account='prelude_test_'+secrets.token_hex(6); password=secrets.token_hex(16)
    hashed=crypt.crypt(password,crypt.mksalt(crypt.METHOD_BLOWFISH))
    uid=int(mysql("INSERT INTO user(userAccount,userPassword,userName,userRole) VALUES ('"+account+"','"+hashed+"','Core template acceptance','user'); SELECT LAST_INSERT_ID();"))
    report={'passed':False,'results':[]}; target=stage/'submission-report.json'
    try:
        api=Api(); api.call('/user/login',{'userAccount':account,'userPassword':password}); api.csrf=None
        q=api.call('/question/get/vo/by-number?problemNo=3')
        for language,code,expected,location in jobs:
            body={'questionId':q['id'],'problemVersion':q['problemVersion'],'executionMode':'FUNCTION','language':language,'code':code,'clientRequestId':str(uuid.uuid4())}
            deadline=time.monotonic()+90
            while True:
                try: sid=api.call('/question/question_submit/do',body);break
                except RuntimeError as error:
                    if 'JUDGE_BUSY' not in str(error) or time.monotonic()>deadline:raise
                    time.sleep(5)
            deadline=time.monotonic()+180
            while time.monotonic()<deadline:
                result=api.call('/question/question_submit/get/vo?id='+str(sid))
                if result['status'] in (2,3):break
                time.sleep(.5)
            else:raise RuntimeError('Submission deadline exceeded')
            verdict=result.get('verdict') or result['judgeInfo']['message']; assert verdict==expected,(language,expected,verdict)
            stored=bytes.fromhex(mysql('SELECT HEX(code) FROM question_submit WHERE id='+str(sid))).decode()
            assert stored==code,'Stored code was modified'
            if location:
                diagnostic=result['diagnostics']['message'];assert location in diagnostic,(location,diagnostic)
                assert '/app/' not in diagnostic and 'import java.util.*;' not in diagnostic
            else:assert result['judgeInfo']['executedMemory'] is not None
            assert int(mysql('SELECT attemptCount FROM question_submit WHERE id='+str(sid)))==1
            report['results'].append({'language':language,'expected':expected,'verdict':verdict,'storedCodeUnchanged':True,'diagnosticLocation':location,'attemptCount':1})
            atomic_json(target,report);print(json.dumps(report['results'][-1]),flush=True)
        report['passed']=len(report['results'])==7
    finally:
        pending=int(mysql('SELECT COUNT(*) FROM question_submit WHERE userId='+str(uid)+' AND status IN (0,1) AND isDelete=0'))
        report['pendingTestSubmissions']=pending
        if not pending:
            assert mysql('SELECT userAccount FROM user WHERE id='+str(uid))==account
            mysql("START TRANSACTION; UPDATE question_submit SET isDelete=1 WHERE userId="+str(uid)+"; UPDATE user SET userRole='ban',isDelete=1 WHERE id="+str(uid)+"; COMMIT;")
            report['testAccountRemoved']=True
        report['statisticsMismatches']=len(mysql(Path('/srv/nemo-oj/deploy/migration/checks/submission_statistics_audit.sql').read_text()).splitlines())
        report['old166Unchanged']=old_fingerprint()==json.loads((stage/'release-report.json').read_text())['old166Fingerprint']
        atomic_json(target,report)
    assert report['passed'] and report['statisticsMismatches']==0 and report['old166Unchanged']


if __name__=='__main__':main(Path(sys.argv[1]))
