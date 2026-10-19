"""Reduce redundant scramble cases by publishing v2, preserving the v1 snapshot."""
import crypt
import hashlib
import json
import secrets
import sys
from pathlib import Path
from catalog_326_release import Api, source, matches, old_fingerprint
sys.path.insert(0, '/usr/local/lib/nemo-ops')
from common import mysql, atomic_json


def main(stage):
    release, manifest = source(stage)
    original = json.loads((stage / 'catalog_326_release_v1.json').read_text())
    for before, after in zip(original, release):
        assert before['problemNo'] == after['problemNo']
        if before['problemNo'] != 325:
            assert before == after
        else:
            assert all(before[k] == after[k] for k in before if k not in ('acm', 'spec'))
            assert all(case in before['acm'] for case in after['acm'])
            assert all(case in before['spec']['judgeCases'] for case in after['spec']['judgeCases'])
            assert len(after['acm']) == len(after['spec']['judgeCases']) == 27
            assert {k: v for k, v in before['spec'].items() if k != 'judgeCases'} == {k: v for k, v in after['spec'].items() if k != 'judgeCases'}
    baseline = json.loads((stage / 'baseline-report.json').read_text())
    assert baseline['oldFingerprint'] == old_fingerprint()
    assert json.loads((stage / 'runtime-report.json').read_text())['passed']
    item = next(q for q in release if q['problemNo'] == 325)
    report = {'passed': False, 'problemNo': 325, 'oldCasesPerMode': 39, 'newCasesPerMode': 27, 'platformLimitsUnchanged': True}
    target = stage / 'revision-report.json'
    account = 'catalog326_admin_' + secrets.token_hex(6); password = secrets.token_hex(16)
    hashed = crypt.crypt(password, crypt.mksalt(crypt.METHOD_BLOWFISH))
    uid = int(mysql("INSERT INTO user(userAccount,userPassword,userName,userRole) VALUES ('" + account + "','" + hashed + "','String catalog budget revision','admin'); SELECT LAST_INSERT_ID();"))
    api = Api()
    try:
        api.call('/user/login', {'userAccount': account, 'userPassword': password}); api.csrf = None
        public = api.call('/question/get/vo/by-number?problemNo=325')
        q = api.call('/question/admin/detail?id=' + str(public['id']))
        assert q['problemVersion'] == 1
        matches(q['id'], next(p for p in original if p['problemNo'] == 325))
        old_id = q['id']
        old_snapshot = mysql('SELECT HEX(JSON_OBJECT(\'spec\',problemSpec,\'cases\',judgeCase,\'content\',content)) FROM question_version WHERE questionId=' + str(old_id) + ' AND version=1')
        assert old_snapshot
        report['oldSnapshotSha256'] = hashlib.sha256(old_snapshot.encode()).hexdigest()
        draft_id = api.call('/question/admin/new_version', {'id': q['id'], 'expectedRowVersion': q['rowVersion']})
        draft = api.call('/question/admin/detail?id=' + str(draft_id))
        body = {key: item[key] for key in ('title', 'tags', 'content', 'answer', 'judgeConfig')}
        body.update(id=draft_id, expectedRowVersion=draft['rowVersion'], judgeCase=item['acm'], problemSpec=item['spec'])
        api.call('/question/update', body)
        draft = api.call('/question/admin/detail?id=' + str(draft_id))
        matches(draft_id, item)
        api.call('/question/admin/publish', {'id': draft_id, 'expectedRowVersion': draft['rowVersion']})
        public = api.call('/question/get/vo/by-number?problemNo=325')
        assert public['id'] == draft_id and public['problemVersion'] == 2
        assert old_snapshot == mysql('SELECT HEX(JSON_OBJECT(\'spec\',problemSpec,\'cases\',judgeCase,\'content\',content)) FROM question_version WHERE questionId=' + str(old_id) + ' AND version=1')
        assert old_fingerprint() == baseline['oldFingerprint']
        report.update(passed=True, previousId=str(old_id), newId=str(draft_id), newVersion=2, oldSnapshotUnchanged=True, old306Unchanged=True)
        for name in ('baseline-report.json', 'publish-report.json'):
            data = json.loads((stage / name).read_text())
            data.setdefault('previousSourceHashes', []).append(data['sha256']); data['sha256'] = manifest['sha256']
            if name == 'publish-report.json':
                for row in data['published']:
                    if row['problemNo'] == 325: row.update(id=str(draft_id), previousId=str(old_id), problemVersion=2)
            atomic_json(stage / name, data)
    finally:
        mysql("UPDATE user SET userRole='ban',isDelete=1 WHERE id=" + str(uid))
        report['fixtureAdminRemoved'] = True; atomic_json(target, report)
    print(json.dumps(report), flush=True)


if __name__ == '__main__': main(Path(sys.argv[1]))
