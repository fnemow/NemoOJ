#!/usr/bin/env python3
"""Run only against the isolated acceptance-services.yml fixture."""
import http.cookiejar, json, os, secrets, time, urllib.request

opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
USER = os.environ.get('ACCEPTANCE_USER_URL', 'http://127.0.0.1:18102/api/user')
QUESTION = os.environ.get('ACCEPTANCE_QUESTION_URL', 'http://127.0.0.1:18103/api/question')

def call(base, path, body=None):
    req = urllib.request.Request(base+path, data=None if body is None else json.dumps(body).encode(),
                                 headers={'Content-Type':'application/json'})
    with opener.open(req, timeout=150) as response:
        result = json.load(response)
    assert result['code'] == 0, (path, result)
    return result['data']

account = 'acceptance_'+secrets.token_hex(5)
password = secrets.token_hex(16)
invitation = os.environ.get('ACCEPTANCE_INVITATION_CODE')
if not invitation:
    raise SystemExit('Set ACCEPTANCE_INVITATION_CODE to a fresh administrator-issued one-time invitation.')
call(USER, '/register', {'userAccount':account,'userPassword':password,'checkPassword':password,
                         'invitationCode':invitation})
call(USER, '/login', {'userAccount':account,'userPassword':password})
print('register/login PASS', flush=True)

def question(mode):
    body={'title':'Acceptance '+mode, 'content':'Isolated integration fixture', 'tags':[],
          'judgeConfig':{'timeLimit':2000,'memoryLimit':262144,'outputLimitBytes':4096},
          'judgeCase':[{'input':'1 2\n','output':'3'}]}
    if mode == 'FUNCTION':
        body['problemSpec']={'mode':'function','className':'Solution','methodName':'solve',
            'parameters':[{'name':'value','type':'int[]'}],'returnType':'int[]',
            'resultSource':'return','harnessVersion':1,
            'judgeCases':[{'input':'{"value":[1,2,3]}','output':'[1,2,3]'}]}
    else:
        body['problemSpec']={'mode':'function','className':'Solution','methodName':'solve',
            'parameters':[{'name':'a','type':'int'},{'name':'b','type':'int'}],
            'returnType':'int','resultSource':'return','harnessVersion':1,
            'judgeCases':[{'input':'{"a":1,"b":2}','output':'3'}]}
    qid=call(QUESTION,'/add',body)
    call(QUESTION,'/publish',{'id':qid})
    return qid

programs={
 'ACM':{
   'java':'public class Main {public static void main(String[] a){System.out.print(3);}}',
   'python':'import sys\nprint(sum(map(int,sys.stdin.read().split())))',
   'cpp':'#include <iostream>\nint main(){long a,b;std::cin>>a>>b;std::cout<<a+b;}'},
 'FUNCTION':{
   'java':'class Solution {public int[] solve(int[] value){return value;}}',
   'python':'class Solution:\n def solve(self,value): return value',
   'cpp':'class Solution {public: std::vector<int> solve(std::vector<int> value){return value;}};'}}
evidence=[]
for mode, languages in programs.items():
    qid=question(mode)
    capabilities=call(QUESTION,'/languages?questionId='+str(qid))
    print(mode+' capabilities '+json.dumps(capabilities,ensure_ascii=False),flush=True)
    for language, code in languages.items():
        sid=call(QUESTION,'/question_submit/do',{'questionId':qid,'language':language,'code':code})
        deadline=time.monotonic()+180
        while time.monotonic()<deadline:
            page=call(QUESTION,'/question_submit/list/page',{'id':sid,'current':1,'pageSize':10})
            row=next((r for r in page['records'] if str(r['id'])==str(sid)),None)
            if row and row.get('status') in (2,3): break
            time.sleep(1)
        else: raise AssertionError(('submission timed out',sid))
        info=row['judgeInfo']
        if isinstance(info,str): info=json.loads(info)
        assert info['message']=='accepted',(mode,language,row)
        assert info.get('runtimeId') and info.get('policyVersion') and info.get('effectiveLimits'),info
        assert info['effectiveLimits']['timeLimitMs']==(6000 if language=='python' else 2000),info
        evidence.append({'mode':mode,'language':language,'id':sid,'judgeInfo':info})
        print(mode,language,'submission',sid,'ACCEPTED',flush=True)
with open('fullchain-evidence.json','w') as f: json.dump(evidence,f,indent=2)
print('Full submit -> MQ -> judge -> sandbox -> database chain PASS',flush=True)
