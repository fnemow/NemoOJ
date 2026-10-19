"""Staged additive catalog release. Credentials stay in memory; reports contain no code."""
import crypt
import hashlib
import http.cookiejar
import json
import math
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
sys.path.insert(0,'/usr/local/lib/nemo-ops')
from common import mysql,atomic_json,container

BASE='https://fw-nemo.cn/api'


class Api:
    def __init__(self):
        self.client=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.csrf=None
    def call(self,path,body=None):
        headers={'Accept':'application/json','Origin':'https://fw-nemo.cn','Referer':'https://fw-nemo.cn/problems'}
        data=None
        if body is not None:
            if self.csrf is None:self.csrf=self.call('/user/csrf')
            headers[self.csrf['headerName']]=self.csrf['token'];headers['Content-Type']='application/json';data=json.dumps(body,ensure_ascii=False).encode()
        try:
            with self.client.open(urllib.request.Request(BASE+path,data=data,headers=headers),timeout=40) as response:result=json.load(response)
        except urllib.error.HTTPError as error:
            try:result=json.load(error)
            except Exception:raise RuntimeError('HTTP '+str(error.code)+' '+path.split('?')[0]) from None
        if result.get('code')!=0:raise RuntimeError('API rejected '+path.split('?')[0]+' '+str(result.get('errorKey'))+' '+str(result.get('message')))
        return result['data']


def old_fingerprint():
    raw=mysql("SELECT HEX(JSON_OBJECT('n',p.problemNo,'id',q.id,'version',q.problemVersion,'state',p.status,'current',p.currentQuestionId,'title',q.title,'content',q.content,'tags',q.tags,'answer',q.answer,'cases',q.judgeCase,'config',q.judgeConfig,'spec',q.problemSpec)) FROM question_public_number p JOIN question q ON q.id=p.currentQuestionId WHERE p.problemNo<=166 ORDER BY p.problemNo;")
    return hashlib.sha256(raw.encode()).hexdigest()


def runtime(stage):
    bundles=json.loads((stage/'sandbox_bundles.json').read_text());report={'bundles':[],'passed':False}
    digest=hashlib.sha256((stage/'sandbox_bundles.json').read_bytes()).hexdigest()
    target=stage/'runtime-report.json'
    if target.exists():report=json.loads(target.read_text())
    if report.get('sha256')!=digest:report={'sha256':digest,'bundles':[],'passed':False}
    metadata=json.loads(subprocess.check_output(['docker','inspect',container('core','sandbox')]))[0]
    ip=next(value['IPAddress'] for value in metadata['NetworkSettings']['Networks'].values() if value['IPAddress'])
    env=dict(item.split('=',1) for item in metadata['Config']['Env']);secret=env['SANDBOX_SECRET']
    for index,bundle in enumerate(bundles):
        if index<len(report['bundles']):continue
        plan={'language':'python','mode':'ACM','entryPoint':'main.py','sourceFiles':[{'fileName':'main.py','content':bundle['code']}],
              'cases':[{'caseIndex':0,'stdin':''}],'limits':{'timeLimitMs':15000,'memoryLimitKB':262144,'outputLimitBytes':1048576}}
        until=time.monotonic()+120
        while True:
            try:
                request=urllib.request.Request('http://'+ip+':8090/api/v2/executeCode',data=json.dumps({'submissionId':0,'backgroundValidation':True,'executionPlan':plan}).encode(),headers={'Content-Type':'application/json','auth':secret})
                with urllib.request.urlopen(request,timeout=80) as response:result=json.load(response)
                break
            except urllib.error.HTTPError as error:
                if error.code!=503 or time.monotonic()>until:raise
                time.sleep(3)
        cases=result.get('cases') or []
        assert result.get('overallStatus')==0 and len(cases)==1,(index,result.get('overallStatus'),result.get('compileMessage'),result.get('systemMessage'),cases)
        case=cases[0]
        assert case['stdout']==bundle['expected'] and case['exitCode']==0,(index,case)
        report['bundles'].append({'index':index,'firstProblemNo':bundle['firstProblemNo'],'problemCount':bundle['problemCount'],
                                  'checks':int(bundle['expected'].split()[1]),'timeMs':case.get('time'),'memoryKiB':case.get('memory')})
        atomic_json(target,report)
        print(json.dumps({'runtimeBundlesPassed':len(report['bundles']),'totalBundles':len(bundles)}),flush=True)
    report.update(passed=True,problems=sum(item['problemCount'] for item in report['bundles']),checks=sum(item['checks'] for item in report['bundles']))
    atomic_json(target,report)


def publish(stage):
    assert json.loads((stage/'runtime-report.json').read_text())['passed']
    source=stage/'catalog_306_release.json';release=json.loads(source.read_text());manifest=json.loads((stage/'catalog_306_manifest.json').read_text())
    assert hashlib.sha256(source.read_bytes()).hexdigest()==manifest['sha256']
    target=stage/'publish-report.json'
    report=json.loads(target.read_text()) if target.exists() else {'sha256':manifest['sha256'],'oldFingerprint':old_fingerprint(),'published':[],'passed':False}
    assert report['oldFingerprint']==old_fingerprint()
    if report['sha256']!=manifest['sha256']:
        # A retry may reduce an unpublished oversized case. Every already
        # published title/content/spec/case remains immutable and must match.
        by_number={item['problemNo']:item for item in release}
        for row in report['published']:
            raw=mysql('SELECT HEX(judgeCase),HEX(problemSpec),HEX(content) FROM question WHERE id='+str(row['id']))
            cases_hex,spec_hex,content_hex=raw.split('\t');item=by_number[row['problemNo']]
            stored_cases=json.loads(bytes.fromhex(cases_hex).decode());stored_spec=json.loads(bytes.fromhex(spec_hex).decode())
            assert stored_cases==item['acm'] and bytes.fromhex(content_hex).decode()==item['content']
            def contains(stored,expected):
                if isinstance(expected,dict):return all(key in stored and contains(stored[key],value) for key,value in expected.items())
                if isinstance(expected,list):return len(stored)==len(expected) and all(contains(a,b) for a,b in zip(stored,expected))
                return stored==expected
            assert contains(stored_spec,item['spec']),'Published spec cannot be changed in place'
        report.setdefault('previousSourceHashes',[]).append(report['sha256']);report['sha256']=manifest['sha256']
    account='catalog306_admin_'+secrets.token_hex(6);password=secrets.token_hex(16)
    hashed=crypt.crypt(password,crypt.mksalt(crypt.METHOD_BLOWFISH))
    uid=int(mysql("INSERT INTO user(userAccount,userPassword,userName,userRole) VALUES ('"+account+"','"+hashed+"','Catalog 306 release operator','admin'); SELECT LAST_INSERT_ID();"))
    api=Api();atomic_json(target,report)
    try:
        api.call('/user/login',{'userAccount':account,'userPassword':password})
        api.csrf=None  # Login rotates the session and its anti-CSRF token.
        for item in release:
            number=item['problemNo'];existing=mysql('SELECT questionId,status FROM question_public_number WHERE problemNo='+str(number))
            if existing:
                qid,state=existing.split('\t');q=api.call('/question/admin/detail?id='+qid)
                assert q['title']==item['title'] and q['content']==item['content'] and q['problemSpec']['methodName']==item['spec']['methodName'],'Existing question is not this release'
                if state=='ONLINE':
                    if not any(row['problemNo']==number for row in report['published']):report['published'].append({'problemNo':number,'id':qid})
                    continue
                assert state=='DRAFT' and not q['isPublished']
            else:
                maximum=int(mysql('SELECT MAX(problemNo) FROM question_public_number'))
                assert maximum==number-1,'Unexpected catalog changes; do not renumber'
                body={key:item[key] for key in ('title','tags','content','answer','judgeConfig')}
                body.update(judgeCase=item['acm'],problemSpec=item['spec'])
                qid=api.call('/question/add',body);q=api.call('/question/admin/detail?id='+str(qid))
                assert int(q['problemNo'])==number,'Unexpected natural number; stop release'
            api.call('/question/admin/publish',{'id':qid,'expectedRowVersion':q['rowVersion']})
            public=api.call('/question/get/vo/by-number?problemNo='+str(number))
            assert public['title']==item['title'] and public['isPublished'] and public['problemVersion']==1
            report['published'].append({'problemNo':number,'id':str(qid)})
            atomic_json(target,report)
            if len(report['published'])%10==0:print(json.dumps({'published':len(report['published']),'total':140}),flush=True)
        assert report['oldFingerprint']==old_fingerprint()
        report.update(passed=len(report['published'])==140,old166Unchanged=True)
    finally:
        mysql("UPDATE user SET userRole='ban',isDelete=1 WHERE id="+str(uid))
        report['fixtureAdminRemoved']=True;atomic_json(target,report)


def verify(stage):
    manifest=json.loads((stage/'catalog_306_manifest.json').read_text());api=Api();report={'details':0,'templates':0,'passed':False}
    records=[]
    for page in range(1,17):
        listing=api.call('/question/list/page/vo',{'current':page,'pageSize':20,'sortField':'problemNo','sortOrder':'ascend'})
        assert listing['total']==306;records.extend(listing['records'])
    assert [record['problemNo'] for record in records]==list(range(1,307))
    for item in manifest['problems']:
        number=item['problemNo'];q=api.call('/question/get/vo/by-number?problemNo='+str(number))
        assert q['title']==item['title'] and q['problemSpec']['methodName']==item['methodName']
        assert len(q['samples'])==2
        # Verify no hidden answers, reference source or editorial is public.
        assert not q.get('judgeCase') and not q.get('answer') and not q['problemSpec'].get('judgeCases')
        capabilities=api.call('/question/languages?questionId='+str(q['id']))
        assert set(capabilities['availableModes'])=={'ACM','FUNCTION'}
        for language in ('java','python','cpp'):
            for mode in ('ACM','FUNCTION'):
                query=urllib.parse.urlencode({'questionId':q['id'],'problemVersion':1,'language':language,'executionMode':mode})
                template=api.call('/question/code_template?'+query)
                assert template['language']==language and template['mode']==mode and template['code'].strip()
                if mode=='FUNCTION':assert item['methodName'] in template['code']
                if language=='cpp':assert 'using namespace std;' in template['code'] and 'int32_t' not in template['code']
                report['templates']+=1
        report['details']+=1
        if report['details']%20==0:print(json.dumps({'detailsVerified':report['details'],'templatesVerified':report['templates']}),flush=True)
    report.update(passed=True,totalProblems=306,continuousNumbers=True)
    atomic_json(stage/'api-report.json',report)


if __name__=='__main__':
    mode=sys.argv[1];stage=Path(sys.argv[2]);{'runtime':runtime,'publish':publish,'verify':verify}[mode](stage)
