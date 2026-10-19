"""Production acceptance using ephemeral fixtures. Output excludes identities, code and tokens."""
import base64
import crypt
import http.cookiejar
import json
import secrets
import struct
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path
sys.path.insert(0, '/usr/local/lib/nemo-ops')
from common import atomic_json, mysql
from redis_session_ops import RedisConnection, DELETE_ANONYMOUS

stage = Path(sys.argv[1]); BASE = 'http://127.0.0.1:18081/api'
report = {}; account = 'session_eval_' + secrets.token_hex(6); password = secrets.token_hex(16)
uid = None; submissions = []; own_sessions = set()

class Api:
    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))
        self.csrf = None
    def send(self, path, body=None, csrf=None):
        headers = {'Accept': 'application/json', 'Origin': 'https://fw-nemo.cn', 'Referer': 'https://fw-nemo.cn/problems'}
        if csrf: headers[csrf['headerName']] = csrf['token']
        if body is not None: headers['Content-Type'] = 'application/json'
        request = urllib.request.Request(BASE + path, data=None if body is None else json.dumps(body).encode(), headers=headers)
        try: response = self.client.open(request, timeout=15)
        except urllib.error.HTTPError as error: response = error
        with response:
            return response.status, json.load(response), response.headers.get('Set-Cookie'), response.headers.get('Cache-Control')
    def call(self, path, body=None):
        if body is not None and self.csrf is None: self.csrf = self.call('/user/csrf')
        status, result, _, _ = self.send(path, body, self.csrf if body is not None else None)
        assert status == 200 and result.get('code') == 0, 'Acceptance API rejected: ' + path.split('?')[0]
        return result['data']
    def key(self):
        cookie = next(c.value for c in self.jar if c.name == 'SESSION')
        sid = base64.b64decode(cookie + '=' * (-len(cookie) % 4)).decode()
        assert str(uuid.UUID(sid)) == sid
        key = ('nemo-oj:session:sessions:' + sid).encode(); own_sessions.add(key); return key

def count_sessions(redis):
    count = 0; cursor = b'0'
    while True:
        cursor, keys = redis.command('SCAN', cursor, 'MATCH', 'nemo-oj:session:sessions:*', 'COUNT', 1000)
        count += len(keys)
        if cursor == b'0': return count

def session_interval(redis, key):
    value = redis.command('HGET', key, 'maxInactiveInterval')
    if value.startswith(b'\xac\xed\x00\x05') and b'java.lang.Integer' in value: return struct.unpack('>i', value[-4:])[0]
    decoded = json.loads(value)
    return decoded[1] if isinstance(decoded, list) else decoded

def save(): atomic_json(stage / 'acceptance.json', report)

try:
    with RedisConnection() as redis:
        before = count_sessions(redis)
        for i in range(100):
            api = Api()
            path = ['/user/get/login', '/question/get/vo/by-number?problemNo=3', '/user/health/readiness'][i % 3]
            status, envelope, cookie, _ = api.send(path)
            assert not cookie, 'Anonymous read created cookie'
            if path == '/user/get/login': assert envelope['code'] == 40100
            elif path.startswith('/question/'): assert envelope['code'] == 0
            else: assert envelope['status'] == 'UP'
        api = Api()
        for _ in range(20):
            _, envelope, cookie, _ = api.send('/question/list/page/vo', {'current': 1, 'pageSize': 10})
            assert envelope['code'] == 0 and not cookie
        after = count_sessions(redis)
        assert after <= before, 'Session count grew during anonymous reads'
        report['anonymousReads'] = {'requests': 120, 'cookiesCreated': 0, 'sessionsBefore': before, 'sessionsAfter': after}
        save()
        api = Api(); status, token, _, cache = api.send('/user/csrf')
        assert status == 200 and token['code'] == 0 and cache == 'no-store'
        api.csrf = token['data']; anonymous_key = api.key()
        assert session_interval(redis, anonymous_key) == 900 and 0 < int(redis.command('PTTL', anonymous_key)) <= 900000
        assert api.call('/user/csrf')['token'] == api.csrf['token'] and api.key() == anonymous_key
        report['anonymousLifetimeSeconds'] = 900
        interval = redis.command('HGET', anonymous_key, 'maxInactiveInterval')
        if interval.startswith(b'\xac\xed\x00\x05') and b'java.lang.Integer' in interval:
            long_interval = interval[:-4] + struct.pack('>i', 2592000)
        else:
            decoded = json.loads(interval)
            long_interval = json.dumps([decoded[0], 2592000] if isinstance(decoded, list) else 2592000).encode()
        redis.command('HSET', anonymous_key, 'maxInactiveInterval', long_interval)
        redis.command('PEXPIRE', anonymous_key, 2592000000)
        _, envelope, _, _ = api.send('/user/get/login')
        assert envelope['code'] == 40100 and session_interval(redis, anonymous_key) == 900
        assert api.key() == anonymous_key and int(redis.command('PTTL', anonymous_key)) <= 900000
        report['legacyAnonymousReadAdoptsShortLifetime'] = True
        redis.command('PEXPIRE', anonymous_key, 1); time.sleep(.05)
        status, envelope, cookie, _ = api.send('/user/login', {'userAccount': account, 'userPassword': password}, api.csrf)
        assert status == 403 and envelope['errorKey'] == 'CSRF_INVALID' and not cookie
        report['expiredCsrfRejectedBeforeLogin'] = True
        api.csrf = api.call('/user/csrf'); before_login = api.key()
        hashed = crypt.crypt(password, crypt.mksalt(crypt.METHOD_BLOWFISH))
        uid = int(mysql("INSERT INTO user(userAccount,userPassword,userName,userRole) VALUES ('" + account + "','" + hashed + "','Session acceptance','user');SELECT LAST_INSERT_ID();"))
        assert str(api.call('/user/login', {'userAccount': account, 'userPassword': password})['id']) == str(uid)
        api.csrf = None; authenticated_key = api.key()
        assert before_login != authenticated_key and redis.command('EXISTS', before_login) == b'0'
        assert session_interval(redis, authenticated_key) == 2592000
        assert int(redis.command('PTTL', authenticated_key)) > 2591900000
        assert str(api.call('/user/get/login')['id']) == str(uid)
        api.csrf = api.call('/user/csrf')
        assert session_interval(redis, authenticated_key) == 2592000
        report['login'] = {'idRotated': True, 'oldSessionRemoved': True, 'lifetimeSeconds': 2592000, 'csrfRotationPreservesLogin': True}
        denied = api.send('/question/admin/problems/list/page', {'current': 1, 'pageSize': 10}, api.csrf)[1]
        assert denied.get('code') == 40101, 'Ordinary user admin denial was not an authorization error'
        report['ordinaryUserAdminDenied'] = True
        problem = api.call('/question/get/vo/by-number?problemNo=3')
        source = 'class Solution:\n def search(self,nums,target):\n  l=0;r=len(nums)-1\n  while l<=r:\n   m=(l+r)//2\n   if nums[m]==target:return m\n   if nums[m]<target:l=m+1\n   else:r=m-1\n  return -1'
        started = time.monotonic()
        sid = api.call('/question/question_submit/do', {'questionId': problem['id'], 'problemVersion': problem['problemVersion'],
              'language': 'python', 'executionMode': 'FUNCTION', 'code': source, 'clientRequestId': str(uuid.uuid4())})
        submissions.append(sid); deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            result = api.call('/question/question_submit/get/vo?id=' + str(sid))
            if result['status'] in (2, 3): break
            time.sleep(.5)
        else: raise RuntimeError('Submission acceptance timed out')
        info = result.get('judgeInfo') or {}; verdict = result.get('verdict') or info.get('message')
        assert verdict == 'accepted' and info.get('executedTime') is not None and info.get('executedMemory') is not None
        report['submission'] = {'accepted': True, 'resourcesPresent': True, 'seconds': round(time.monotonic()-started, 3)}
        assert api.call('/user/logout', {}) is True
        assert redis.command('EXISTS', authenticated_key) == b'0'
        status, envelope, cookie, _ = api.send('/user/get/login')
        assert envelope['code'] == 40100 and not cookie
        report['logoutDeletesSession'] = True
        # Exercise the actual Lua protection against promotion or timestamp races.
        key = ('nemo-oj:session:sessions:' + str(uuid.uuid4())).encode(); own_sessions.add(key)
        accessed = str(int(time.time()*1000) - 2*86400000).encode()
        redis.command('HSET', key, 'lastAccessedTime', accessed, 'sessionAttr:user_login', b'opaque-test')
        assert redis.command('EVAL', DELETE_ANONYMOUS, 1, key, accessed) == b'0'
        redis.command('HDEL', key, 'sessionAttr:user_login'); redis.command('HSET', key, 'lastAccessedTime', str(int(time.time()*1000)))
        assert redis.command('EVAL', DELETE_ANONYMOUS, 1, key, accessed) == b'0'
        redis.command('HSET', key, 'lastAccessedTime', accessed)
        assert redis.command('EVAL', DELETE_ANONYMOUS, 1, key, accessed) == b'1'
        report['cleanupLuaProtection'] = True
        # Rejections must not continue allocating new sessions. Uses loopback acceptance IP.
        limit_api = Api(); success = rejected = 0
        for _ in range(65):
            _, envelope, cookie, _ = limit_api.send('/user/csrf')
            if envelope.get('code') == 0:
                success += 1; limit_api.key()
            else:
                rejected += 1; assert not cookie
        assert 0 < success <= 60 and rejected > 0
        report['csrfRateLimit'] = {'successful': success, 'rejected': rejected, 'rejectionsAllocateNoCookies': True}
        report['passed'] = True; save()
finally:
    with RedisConnection() as redis:
        for key in own_sessions: redis.command('DEL', key)
    if uid:
        assert mysql('SELECT COUNT(*) FROM question_submit WHERE userId=' + str(uid) + ' AND status IN (0,1)') == '0', 'Fixture still judging'
        mysql('START TRANSACTION;UPDATE question_submit SET isDelete=1 WHERE userId=' + str(uid) + ';UPDATE user SET userRole=\'ban\',isDelete=1 WHERE id=' + str(uid) + ';COMMIT;')
    report['fixtureCleanupComplete'] = True; save()
print(json.dumps(report), flush=True)
