#!/usr/bin/env python3
"""Opt-in acceptance against an isolated Linux Docker sandbox. No host code execution."""
import json, os, urllib.request
BASE = os.environ['SANDBOX_BASE_URL'].rstrip('/')
SECRET = os.environ['SANDBOX_SECRET']

def request(path, payload=None):
    data = None if payload is None else json.dumps(payload).encode()
    req = urllib.request.Request(BASE + '/api' + path, data=data,
                                 headers={'auth':SECRET, 'Content-Type':'application/json'})
    with urllib.request.urlopen(req, timeout=650) as response:
        return json.load(response)

def execute(language, code, status, stdin='', expected=None, time_ms=1000, output=4096):
    file, entry = {'java':('Main.java','Main'), 'python':('main.py','main.py'), 'cpp':('main.cpp','main')}[language]
    plan = {'language':language, 'mode':'ACM', 'entryPoint':entry,
            'sourceFiles':[{'fileName':file, 'content':code}],
            'cases':[{'caseIndex':0, 'stdin':stdin}],
            'limits':{'timeLimitMs':time_ms, 'memoryLimitKB':262144, 'outputLimitBytes':output}}
    result = request('/v2/executeCode', {'submissionId':0, 'executionPlan':plan})
    assert result['overallStatus'] == status, (language, status, result)
    if status == 0:
        assert result['cases'][0]['stdout'].strip() == expected, result
        assert result['effectiveLimits']['timeLimitMs'] == time_ms * (3 if language == 'python' else 1), result
    print(language, 'status', status, 'PASS', flush=True)

capabilities = request('/v2/capabilities')
ready = {c['language'] for c in capabilities if c.get('imageReady')}
assert ready == {'java','python','cpp'}, capabilities
execute('java', 'public class Main {public static void main(String[] a){System.out.print(3);}}',0,expected='3')
execute('python','print(sum(map(int,input().split())))',0,'1 2\n','3')
execute('cpp','#include <iostream>\nint main(){long a,b;std::cin>>a>>b;std::cout<<a+b;}',0,'1 2\n','3')
execute('python','import sys\nprint(sum(map(int,sys.stdin.read().split())))',0,'1 2','3')
execute('cpp','#include <iostream>\nint main(){long a,s=0;while(std::cin>>a)s+=a;std::cout<<s;}',0,'1 2','3')
for lang in ('python','cpp'):
    execute(lang, 'this is invalid syntax !', 1)
execute('python','raise RuntimeError("test")',2)
execute('cpp','int main(){return 1;}',2)
execute('python','while True: pass',3,time_ms=100)
execute('cpp','int main(){while(true){}}',3,time_ms=100)
execute('python','print("x"*100000)',5)
execute('cpp','#include <iostream>\nint main(){for(int i=0;i<100000;++i)std::cout<<"x";}',5)
execute('python','a=[]\nwhile True: a.append(bytearray(4*1024*1024))',4,time_ms=5000)
execute('cpp','#include <vector>\nint main(){std::vector<std::vector<char>> a;for(;;)a.emplace_back(4*1024*1024,1);}',4,time_ms=5000)
print('ACM sandbox acceptance passed. FUNCTION/native harness tests and full MQ acceptance remain separate.')
