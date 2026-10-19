import json
import time
import unittest
from redis_session_ops import maintain, timestamp

class FakeRedis:
    def __init__(self, records, concurrent_change=False):
        self.records = records; self.deleted = []; self.concurrent_change = concurrent_change
    def command(self, *args):
        if args[0] == 'SCAN': return b'0', list(self.records)
        if args[0] == 'TYPE': return b'hash'
        if args[0] == 'HGETALL': return [item for pair in self.records[args[1]].items() for item in pair]
        if args[0] == 'INFO': return b'used_memory:10\r\nmaxmemory:100\r\n'
        if args[0] == 'DBSIZE': return str(len(self.records)).encode()
        if args[0] == 'EVAL':
            if self.concurrent_change: return b'0'
            self.deleted.append(args[3]); return b'1'
        raise AssertionError('Unexpected command')

class SessionMaintenanceTest(unittest.TestCase):
    def test_only_old_recognizable_anonymous_records_are_deleted(self):
        now = int(time.time() * 1000); old = json.dumps(now - 2 * 86400000).encode()
        client = FakeRedis({b'old': {b'lastAccessedTime': old}, b'fresh': {b'lastAccessedTime': str(now).encode()},
                            b'login': {b'lastAccessedTime': old, b'sessionAttr:user_login': b'opaque'},
                            b'unknown': {b'lastAccessedTime': b'opaque'},
                            b'other': {b'lastAccessedTime': old, b'sessionAttr:future-field': b'value'}})
        result = maintain(client, 'clean')
        self.assertEqual(client.deleted, [b'old'])
        self.assertEqual(result['protected'], 2)
        self.assertEqual(result['unknownTimestamp'], 1)
        self.assertNotIn('records', result)
    def test_inspect_never_deletes_and_bounded_cleanup_stops(self):
        old = str(int(time.time() * 1000) - 2 * 86400000).encode()
        client = FakeRedis({str(i).encode(): {b'lastAccessedTime': old} for i in range(5)})
        self.assertEqual(maintain(client, 'inspect')['eligible'], 5)
        self.assertEqual(client.deleted, [])
        result = maintain(client, 'clean', limit=2)
        self.assertEqual(result['removed'], 2)
        self.assertTrue(result['boundedStop'])
    def test_changed_session_is_not_deleted(self):
        old = str(int(time.time() * 1000) - 2 * 86400000).encode()
        self.assertEqual(maintain(FakeRedis({b'old': {b'lastAccessedTime': old}}, True), 'clean')['removed'], 0)
    def test_unknown_or_future_timestamp_is_rejected(self):
        now = int(time.time() * 1000)
        self.assertIsNone(timestamp(b'{"bad":"value"}', now))
        self.assertIsNone(timestamp(str(now + 120000).encode(), now))
        self.assertIsNone(timestamp(b'true', now))
        self.assertEqual(timestamp(json.dumps(['java.lang.Long', now]).encode(), now), now)

if __name__ == '__main__': unittest.main()
