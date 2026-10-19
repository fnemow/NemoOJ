"""Add twenty original string problems through admin APIs; never overwrite old data."""
import crypt
import hashlib
import json
import math
import secrets
import subprocess
import sys
import urllib.parse
import urllib.request
from pathlib import Path
from catalog_306_release import Api, runtime
sys.path.insert(0, '/usr/local/lib/nemo-ops')
from common import mysql, atomic_json, container


def old_fingerprint():
    raw = mysql("SELECT HEX(JSON_OBJECT('n',p.problemNo,'id',q.id,'version',q.problemVersion,'state',p.status,'current',p.currentQuestionId,'title',q.title,'content',q.content,'tags',q.tags,'answer',q.answer,'cases',q.judgeCase,'config',q.judgeConfig,'spec',q.problemSpec)) FROM question_public_number p JOIN question q ON q.id=p.currentQuestionId WHERE p.problemNo<=306 ORDER BY p.problemNo;")
    return hashlib.sha256(raw.encode()).hexdigest()


def source(stage):
    path = stage / 'catalog_326_release.json'
    release = json.loads(path.read_text())
    manifest = json.loads((stage / 'catalog_326_manifest.json').read_text())
    assert hashlib.sha256(path.read_bytes()).hexdigest() == manifest['sha256']
    assert [q['problemNo'] for q in release] == list(range(307, 327))
    return release, manifest


def budget(stage):
    release, manifest = source(stage)
    meta = json.loads(subprocess.check_output(['docker', 'inspect', container('core', 'sandbox')]))[0]
    ip = next(n['IPAddress'] for n in meta['NetworkSettings']['Networks'].values() if n['IPAddress'])
    env = dict(item.split('=', 1) for item in meta['Config']['Env'])
    request = urllib.request.Request('http://' + ip + ':8090/api/v2/capabilities', headers={'auth': env['SANDBOX_SECRET']})
    with urllib.request.urlopen(request, timeout=20) as response: policies = json.load(response)
    report = {'passed': False, 'sha256': manifest['sha256'], 'checks': [], 'policies': {}}
    for language in ('java', 'python', 'cpp'):
        policy = next(p for p in policies if p['language'] == language)
        assert policy['imageReady']
        factor = policy.get('runtimeFactor', 1)
        compile_ms = policy.get('compileTimeoutSeconds', 30) * 1000
        maximum = policy.get('maxTaskTimeMs', 600000)
        report['policies'][language] = {'runtimeFactor': factor, 'compileTimeoutMs': compile_ms, 'maxTaskTimeMs': maximum}
        for q in release:
            effective = math.ceil(q['judgeConfig']['timeLimit'] * factor)
            worst = compile_ms + len(q['acm']) * (effective + 10000)
            assert worst <= maximum, (q['problemNo'], language, worst, maximum)
            report['checks'].append({'problemNo': q['problemNo'], 'language': language, 'worstBudgetMs': worst})
    report['passed'] = True; atomic_json(stage / 'runtime-budget-report.json', report)
    print(json.dumps({'budgetChecks': len(report['checks']), 'passed': True}), flush=True)


def prepare(stage):
    release, manifest = source(stage)
    budget(stage)
    target = stage / 'baseline-report.json'
    if target.exists():
        report = json.loads(target.read_text())
        assert report['sha256'] == manifest['sha256'] and report['oldFingerprint'] == old_fingerprint()
        print(json.dumps({'baselineRechecked': True}), flush=True)
        return
    rows = [json.loads(bytes.fromhex(line).decode()) for line in mysql("SELECT HEX(JSON_OBJECT('number',p.problemNo,'title',q.title,'method',JSON_UNQUOTE(JSON_EXTRACT(q.problemSpec,'$.methodName')))) FROM question_public_number p JOIN question q ON q.id=p.currentQuestionId WHERE p.status='ONLINE' AND q.isDelete=0 ORDER BY p.problemNo;").splitlines()]
    assert [q['number'] for q in rows] == list(range(1, 307)), 'Live catalog differs from expected baseline'
    assert int(mysql('SELECT MAX(problemNo) FROM question_public_number')) == 306
    assert not {q['title'] for q in rows} & {q['title'] for q in release}
    assert not {q['method'] for q in rows} & {q['spec']['methodName'] for q in release}
    report = {'passed': True, 'baseCount': 306, 'sha256': manifest['sha256'], 'oldFingerprint': old_fingerprint(), 'duplicateTitles': 0, 'duplicateMethods': 0}
    atomic_json(target, report)
    print(json.dumps(report), flush=True)


def contains(stored, expected):
    if isinstance(expected, dict):
        return isinstance(stored, dict) and all(key in stored and contains(stored[key], value) for key, value in expected.items())
    if isinstance(expected, list):
        return isinstance(stored, list) and len(stored) == len(expected) and all(contains(a, b) for a, b in zip(stored, expected))
    return stored == expected


def matches(qid, item):
    raw = mysql('SELECT HEX(JSON_OBJECT(\'title\',title,\'content\',content,\'tags\',tags,\'answer\',answer,\'config\',judgeConfig,\'cases\',judgeCase,\'spec\',problemSpec)) FROM question WHERE id=' + str(qid))
    q = json.loads(bytes.fromhex(raw).decode())
    assert all(q[key] == item[key] for key in ('title', 'content', 'answer')), 'Existing question differs from append release'
    for key, expected in [('tags', item['tags']), ('config', item['judgeConfig']), ('cases', item['acm']), ('spec', item['spec'])]:
        assert contains(json.loads(q[key]), expected), 'Existing question data differs: ' + key


def publish(stage):
    release, manifest = source(stage)
    budget(stage)
    baseline = json.loads((stage / 'baseline-report.json').read_text())
    assert baseline['passed'] and baseline['oldFingerprint'] == old_fingerprint()
    assert json.loads((stage / 'runtime-report.json').read_text())['passed']
    target = stage / 'publish-report.json'
    report = json.loads(target.read_text()) if target.exists() else {'sha256': manifest['sha256'], 'oldFingerprint': baseline['oldFingerprint'], 'published': [], 'passed': False}
    assert report['sha256'] == manifest['sha256']
    account = 'catalog326_admin_' + secrets.token_hex(6)
    password = secrets.token_hex(16)
    hashed = crypt.crypt(password, crypt.mksalt(crypt.METHOD_BLOWFISH))
    uid = int(mysql("INSERT INTO user(userAccount,userPassword,userName,userRole) VALUES ('" + account + "','" + hashed + "','String catalog release operator','admin'); SELECT LAST_INSERT_ID();"))
    api = Api()
    try:
        api.call('/user/login', {'userAccount': account, 'userPassword': password}); api.csrf = None
        for item in release:
            number = item['problemNo']
            existing = mysql('SELECT COALESCE(currentQuestionId,questionId),status FROM question_public_number WHERE problemNo=' + str(number))
            if existing:
                qid, state = existing.split('\t'); matches(qid, item)
                q = api.call('/question/admin/detail?id=' + qid)
                assert state == 'ONLINE' or state == 'DRAFT' and not q['isPublished']
            else:
                assert int(mysql('SELECT MAX(problemNo) FROM question_public_number')) == number - 1, 'Unexpected numbering; stop append'
                body = {key: item[key] for key in ('title', 'tags', 'content', 'answer', 'judgeConfig')}
                body.update(judgeCase=item['acm'], problemSpec=item['spec'])
                qid = api.call('/question/add', body)
                q = api.call('/question/admin/detail?id=' + str(qid))
                assert int(q['problemNo']) == number
                state = 'DRAFT'
                matches(qid, item)
            if state != 'ONLINE':
                api.call('/question/admin/publish', {'id': qid, 'expectedRowVersion': q['rowVersion']})
            public = api.call('/question/get/vo/by-number?problemNo=' + str(number))
            assert public['title'] == item['title'] and public['isPublished'] and public['problemVersion'] >= 1
            if not any(row['problemNo'] == number for row in report['published']):
                report['published'].append({'problemNo': number, 'id': str(qid)})
            atomic_json(target, report)
            print(json.dumps({'published': len(report['published']), 'total': 20, 'problemNo': number}), flush=True)
        assert baseline['oldFingerprint'] == old_fingerprint()
        report.update(passed=len(report['published']) == 20, old306Unchanged=True)
    finally:
        mysql("UPDATE user SET userRole='ban',isDelete=1 WHERE id=" + str(uid))
        report['fixtureAdminRemoved'] = True; atomic_json(target, report)


def verify(stage):
    _, manifest = source(stage); api = Api()
    budget(stage)
    report = {'details': 0, 'templates': 0, 'passed': False}
    records = []
    for page in range(1, 18):
        listing = api.call('/question/list/page/vo', {'current': page, 'pageSize': 20, 'sortField': 'problemNo', 'sortOrder': 'ascend'})
        assert listing['total'] == 326; records.extend(listing['records'])
    assert [q['problemNo'] for q in records] == list(range(1, 327))
    for item in manifest['problems']:
        q = api.call('/question/get/vo/by-number?problemNo=' + str(item['problemNo']))
        assert q['title'] == item['title'] and q['problemSpec']['methodName'] == item['methodName']
        assert len(q['samples']) == 2 and not q.get('judgeCase') and not q.get('answer') and not q['problemSpec'].get('judgeCases')
        capabilities = api.call('/question/languages?questionId=' + str(q['id']))
        assert set(capabilities['availableModes']) == {'ACM', 'FUNCTION'}
        for language in ('java', 'python', 'cpp'):
            for mode in ('ACM', 'FUNCTION'):
                query = urllib.parse.urlencode({'questionId': q['id'], 'problemVersion': q['problemVersion'], 'language': language, 'executionMode': mode})
                template = api.call('/question/code_template?' + query)
                code = template['code']
                assert template['language'] == language and template['mode'] == mode and code.strip()
                if mode == 'FUNCTION':
                    assert item['methodName'] in code
                    assert not any(token in code for token in ('#include', 'import java.', 'using namespace std;'))
                if language == 'cpp': assert 'int32_t' not in code
                report['templates'] += 1
        report['details'] += 1
    assert old_fingerprint() == json.loads((stage / 'baseline-report.json').read_text())['oldFingerprint']
    report.update(passed=True, totalProblems=326, continuousNumbers=True, old306Unchanged=True, hiddenCasesNotPublic=True)
    atomic_json(stage / 'api-report.json', report); print(json.dumps(report), flush=True)


if __name__ == '__main__':
    {'prepare': prepare, 'runtime': runtime, 'budget': budget, 'publish': publish, 'verify': verify}[sys.argv[1]](Path(sys.argv[2]))
