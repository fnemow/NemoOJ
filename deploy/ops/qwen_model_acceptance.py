"""Real Qwen AC/CE analyses, quota/cache/cost receipts; no secrets in evidence."""
import crypt
import json
import secrets
import sys
import time
import uuid
from pathlib import Path
sys.path.insert(0, '/usr/local/lib/nemo-ops')
from common import mysql, atomic_json
from cloud_acceptance import CODES
from catalog_306_release import Api


def main(output):
    account = 'qwen_smoke_' + secrets.token_hex(7); password = secrets.token_hex(16)
    hashed = crypt.crypt(password, crypt.mksalt(crypt.METHOD_BLOWFISH))
    uid = int(mysql("INSERT INTO user(userAccount,userPassword,userName,userRole) VALUES ('" + account + "','" + hashed + "','Qwen switch acceptance','user'); SELECT LAST_INSERT_ID();"))
    report = {'passed': False, 'started': time.time(), 'results': []}; api = Api()
    try:
        api.call('/user/login', {'userAccount': account, 'userPassword': password}); api.csrf = None
        deadline = time.monotonic() + 120
        while not api.call('/question/ai/entitlements')['analysisEnabled']:
            assert time.monotonic() < deadline, 'Agent availability did not recover'
            time.sleep(3)
        q = api.call('/question/get/vo/by-number?problemNo=3')
        samples = [('python', CODES['python'], 'accepted'),
                   ('java', 'class Solution {public int search(int[] nums,int target){return ;}}', 'compile_error')]
        for language, code, expected in samples:
            body = {'questionId': q['id'], 'problemVersion': q['problemVersion'], 'language': language,
                    'executionMode': 'FUNCTION', 'code': code, 'clientRequestId': str(uuid.uuid4())}
            deadline = time.monotonic() + 90
            while True:
                try: sid = str(api.call('/question/question_submit/do', body)); break
                except RuntimeError as error:
                    if 'JUDGE_BUSY' not in str(error) or time.monotonic() > deadline: raise
                    time.sleep(3)
            deadline = time.monotonic() + 180
            while True:
                submission = api.call('/question/question_submit/get/vo?id=' + sid)
                if submission['status'] in (2, 3): break
                assert time.monotonic() < deadline, 'Judge deadline exceeded'
                time.sleep(.5)
            assert submission['verdict'] == expected
            before = api.call('/question/ai/entitlements')['remainingToday']; started = time.monotonic()
            created = api.call('/question/ai/analyses', {'submissionId': sid, 'idempotencyKey': str(uuid.uuid4())})
            tid = created['taskId']; deadline = time.monotonic() + 240
            while True:
                task = api.call('/question/ai/tasks/' + tid)
                if task['status'] in ('SUCCEEDED', 'FAILED', 'CANCELLED'): break
                assert time.monotonic() < deadline, 'Analysis deadline exceeded'
                time.sleep(1)
            assert task['status'] == 'SUCCEEDED', task.get('errorCode')
            analysis = api.call('/question/ai/analyses/' + sid)
            assert analysis['modelId'] == 'qwen3.8-flash' and analysis['report']['likelyCause'].strip()
            after = api.call('/question/ai/entitlements')['remainingToday']; assert after == before - 1
            reused = api.call('/question/ai/analyses', {'submissionId': sid, 'idempotencyKey': str(uuid.uuid4())})
            assert reused.get('reused') is True and api.call('/question/ai/entitlements')['remainingToday'] == after
            usage = mysql("SELECT state,reservedCost,inputTokens,outputTokens,estimatedCost FROM ai_usage WHERE taskId='" + tid + "'").split('\t')
            calls = [line.split('\t') for line in mysql("SELECT state,inputTokens,outputTokens,estimatedCost,reservedCost FROM ai_model_call WHERE taskId='" + tid + "'").splitlines()]
            assert usage[0] == 'COMMITTED' and float(usage[1]) == 0
            assert calls and all(c[0] == 'KNOWN' and float(c[4]) == 0 for c in calls)
            assert sum(int(c[1]) for c in calls) == int(usage[2]) > 0
            assert sum(int(c[2]) for c in calls) == int(usage[3]) > 0
            assert abs(sum(float(c[3]) for c in calls) - float(usage[4])) < 1e-8
            row = {'verdict': expected, 'modelId': analysis['modelId'], 'analysisSeconds': round(time.monotonic() - started, 3),
                   'suggestions': len(analysis['report'].get('suggestions', [])), 'cacheReused': True, 'quotaUsed': 1,
                   'knownProviderCalls': len(calls), 'inputTokens': int(usage[2]), 'outputTokens': int(usage[3]),
                   'estimatedCostUsd': usage[4], 'usageCommitted': True}
            report['results'].append(row); atomic_json(output, report); print(json.dumps(row), flush=True)
        assert int(mysql('SELECT COUNT(*) FROM ai_task WHERE ownerUserId=' + str(uid))) == 2
        report['passed'] = True
    finally:
        pending = int(mysql('SELECT COUNT(*) FROM question_submit WHERE userId=' + str(uid) + ' AND status IN (0,1) AND isDelete=0'))
        pending_ai = int(mysql("SELECT COUNT(*) FROM ai_task WHERE ownerUserId=" + str(uid) + " AND status IN ('QUEUED','RUNNING')"))
        report['fixturesRemoved'] = pending == pending_ai == 0
        if report['fixturesRemoved']:
            mysql('START TRANSACTION; UPDATE question_submit SET isDelete=1 WHERE userId=' + str(uid) + "; UPDATE user SET userRole='ban',isDelete=1 WHERE id=" + str(uid) + '; COMMIT;')
        report['usageLedgerPreserved'] = True; report['ended'] = time.time(); atomic_json(output, report)
    print(json.dumps({'passed': report['passed'], 'realAnalyses': len(report['results']), 'fixturesRemoved': report['fixturesRemoved']}), flush=True)


if __name__ == '__main__': main(Path(sys.argv[1]))
