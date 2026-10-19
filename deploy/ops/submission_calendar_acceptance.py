"""Temporary calendar fixtures and public API acceptance; no credentials in evidence."""
import crypt, datetime, http.cookiejar, json, os, secrets, sys, time, urllib.error, urllib.request, uuid
from pathlib import Path
from zoneinfo import ZoneInfo
sys.path.insert(0,'/usr/local/lib/nemo-ops')
sys.path.insert(0,'/home/ubuntu/nemo-catalog-306-20261011')
from common import mysql, atomic_json
from catalog_306_release import Api, BASE

stage=Path(sys.argv[2]);state=stage/'browser-state-private.json';meta=stage/'fixture-record.json'

def raw(api,path):
    req=urllib.request.Request(BASE+path,headers={'Accept':'application/json','Origin':'https://fw-nemo.cn'})
    try:response=api.client.open(req,timeout=20)
    except urllib.error.HTTPError as error:response=error
    with response:return response.status,json.load(response),response.headers.get('Cache-Control','')

def cleanup():
    if state.exists():
        api=Api()
        # Logging out before disabling the test owner revokes the browser session.
        for item in json.loads(state.read_text())['cookies']:
            jar=next(h.cookiejar for h in api.client.handlers if isinstance(h,urllib.request.HTTPCookieProcessor))
            jar.set_cookie(http.cookiejar.Cookie(0,item['name'],item['value'],None,False,item['domain'],True,
                item['domain'].startswith('.'),item['path'],True,item['secure'],None,True,None,None,{'HttpOnly':None},False))
        try:api.call('/user/logout',{})
        finally:state.unlink()
    if meta.exists():
        fixture=json.loads(meta.read_text());uids=fixture['users']
        for user in uids:
            assert mysql('SELECT userAccount FROM user WHERE id='+str(user['id']))==user['account']
        owner_ids=','.join(str(user['id']) for user in uids)
        mysql('START TRANSACTION; UPDATE question_submit SET isDelete=1 WHERE userId IN ('+owner_ids+'); '
              +"UPDATE user SET isDelete=1,userRole='ban' WHERE id IN ("+owner_ids+'); COMMIT;')
        if fixture.get('privateId'):
            mysql("UPDATE private_problem SET status='ARCHIVED' WHERE id='"+fixture['privateId']+"'")
        atomic_json(stage/'cleanup-report.json',{'passed':True,'testAccountsRemoved':len(uids),
            'activeTestSubmissions':int(mysql('SELECT COUNT(*) FROM question_submit WHERE userId IN ('+owner_ids+') AND isDelete=0'))})

if sys.argv[1]=='cleanup':cleanup();print('Calendar test sessions revoked and fixtures soft-deleted');sys.exit(0)
assert sys.argv[1]=='setup' and not meta.exists() and not state.exists()
fixture={'users':[]};apis=[];report={'passed':False,'checks':[]};target=stage/'api-report.json'
try:
    for role in ['user','user','admin']:
        account='calendar_'+role+'_'+secrets.token_hex(5);password=secrets.token_hex(16)
        hashed=crypt.crypt(password,crypt.mksalt(crypt.METHOD_BLOWFISH))
        uid=int(mysql("INSERT INTO user(userAccount,userPassword,userName,userRole) VALUES ('"+account+"','"+hashed+"','提交日历验收','"+role+"'); SELECT LAST_INSERT_ID()"))
        fixture['users'].append({'id':uid,'account':account});atomic_json(meta,fixture)
        api=Api();api.call('/user/login',{'userAccount':account,'userPassword':password});api.csrf=None;apis.append(api)
    owner,other=fixture['users'][0]['id'],fixture['users'][1]['id']
    now=datetime.datetime.now(ZoneInfo('Asia/Shanghai'));month=now.strftime('%Y-%m')
    first=now.date().replace(day=1);previous=(first-datetime.timedelta(days=1));next_month=(first.replace(day=28)+datetime.timedelta(days=4)).replace(day=1)
    q1=int(mysql('SELECT currentQuestionId FROM question_public_number WHERE problemNo=1'))
    q2=int(mysql('SELECT currentQuestionId FROM question_public_number WHERE problemNo=3'))
    private_id=str(uuid.uuid4());versions=[str(uuid.uuid4()),str(uuid.uuid4())];fixture['privateId']=private_id;atomic_json(meta,fixture)
    mysql("INSERT INTO private_problem(id,ownerUserId,title,tagsJson,difficulty,status,generationTaskId) VALUES ('"+private_id+"',"+str(owner)+",'Calendar acceptance fixture','[]','EASY','ARCHIVED','"+str(uuid.uuid4())+"')")
    for number,version in enumerate(versions,1):
        mysql("INSERT INTO private_problem_version(id,privateProblemId,versionNumber,contentMarkdown,sampleJson,problemSpecJson,judgeCaseJson,judgeConfigJson,referenceJson,validationJson,contentHash,status) VALUES ('"+version+"','"+private_id+"',"+str(number)+",'Calendar-only fixture','[]','{}','[]','{}','{}','{}','"+secrets.token_hex(32)+"','REJECTED')")
    values=[]
    def add(uid,date,q=q1,private=None,deleted=0,status=2):
        values.append("('python','',"+str(status)+","+('NULL' if private else str(q))+","+str(uid)+",'"+('PRIVATE' if private else 'PUBLIC')+"',"+("'"+private+"'" if private else 'NULL')+",'"+date+"',"+str(deleted)+")")
    add(owner,month+'-01 00:00:00');add(owner,month+'-01 23:59:59',status=3)
    add(owner,month+'-01 12:00:00',q=q2)
    add(owner,month+'-01 12:01:00',private=versions[0]);add(owner,month+'-01 12:02:00',private=versions[1])
    add(owner,month+'-01 12:03:00',deleted=1);add(owner,month+'-02 00:00:00')
    for _ in range(20):add(owner,month+'-03 12:00:00')
    add(owner,str(previous)+' 23:59:59');add(owner,str(next_month)+' 00:00:00')
    add(other,month+'-01 12:00:00',q=q2)
    mysql('INSERT INTO question_submit(language,code,status,questionId,userId,problemSource,privateProblemVersionId,createTime,isDelete) VALUES '+','.join(values))
    path='/question/question_submit/my/activity?month='+month
    started=time.monotonic();status,body,cache=raw(apis[0],path);elapsed=(time.monotonic()-started)*1000
    assert body.get('code')==0,body
    data=body['data'];days={row['date']:row for row in data['days']}
    assert days[month+'-01']=={'date':month+'-01','submissions':5,'problems':3},days[month+'-01']
    assert days[month+'-02']['submissions']==1 and days[month+'-03']['submissions']==20
    assert sum(row['submissions'] for row in data['days'])==26
    assert 'no-store' in cache and data['timezone']=='Asia/Shanghai'
    forged=apis[0].call(path+'&userId='+str(other));assert forged==data
    other_days={row['date']:row for row in apis[1].call(path)['days']};assert other_days[month+'-01']['submissions']==1
    old=apis[0].call('/question/question_submit/my/activity?month='+previous.strftime('%Y-%m'));assert sum(row['submissions'] for row in old['days'])==1
    for bad in [str(next_month)[:7],'2026-99','2026-1','not-a-month']:
        assert raw(apis[0],'/question/question_submit/my/activity?month='+bad)[1].get('code')==40000
    assert raw(Api(),path)[1].get('code') in (40100,40101,40300)
    assert raw(apis[2],path)[1].get('code')==40101
    report.update(passed=True,month=month,currentMonthSubmissions=26,firstDayProblems=3,
        ownerIsolation=True,forgedOwnerIgnored=True,privateVersionsDeduplicated=True,deletedExcluded=True,
        failedSubmissionIncluded=True,monthBoundaryCorrect=True,invalidMonthRejected=True,
        anonymousRejected=True,adminRejected=True,responseNoStore=True,requestMs=round(elapsed,2))
    jar=next(h.cookiejar for h in apis[0].client.handlers if isinstance(h,urllib.request.HTTPCookieProcessor))
    state.write_text(json.dumps({'cookies':[{'name':c.name,'value':c.value,'domain':c.domain,'path':c.path,
        'expires':c.expires if c.expires is not None else -1,'httpOnly':True,'secure':c.secure,'sameSite':'Lax'} for c in jar],'origins':[]}),encoding='utf-8');state.chmod(0o600)
    for api in apis[1:]:api.call('/user/logout',{})
    atomic_json(target,report);print(json.dumps(report),flush=True)
except Exception:
    cleanup();atomic_json(target,report);raise
