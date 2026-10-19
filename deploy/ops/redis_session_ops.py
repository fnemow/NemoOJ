"""Bounded Redis session maintenance. Credentials and session values stay on host."""
import json
import socket
import struct
import subprocess
import time

SESSION_PATTERN = 'nemo-oj:session:sessions:*'
ALLOWED_FIELDS = {b'creationTime', b'lastAccessedTime', b'maxInactiveInterval', b'sessionAttr:NEMO_CSRF_TOKEN'}
DELETE_ANONYMOUS = """#!lua flags=allow-oom
if redis.call('TYPE',KEYS[1]).ok~='hash' then return 0 end
if redis.call('HGET',KEYS[1],'lastAccessedTime')~=ARGV[1] then return 0 end
local fs=redis.call('HKEYS',KEYS[1]);for _,field in ipairs(fs) do
 if field~='creationTime' and field~='lastAccessedTime' and field~='maxInactiveInterval' and field~='sessionAttr:NEMO_CSRF_TOKEN' then return 0 end
end
return redis.call('DEL',KEYS[1])"""

class RedisConnection:
    def __init__(self):
        meta = json.loads(subprocess.check_output(['docker', 'inspect', 'nemo-oj-redis-1']))[0]
        args = meta['Config']['Cmd']
        secret = args[args.index('--requirepass') + 1]
        ip = next(iter(meta['NetworkSettings']['Networks'].values()))['IPAddress']
        self.socket = socket.create_connection((ip, 6379), 3)
        self.file = self.socket.makefile('rb')
        if self.command('AUTH', secret) != b'OK':
            self.close()
            raise RuntimeError('Redis authentication failed; details withheld')

    def reply(self):
        line = self.file.readline()
        kind, value = line[:1], line[1:-2]
        if kind == b'*': return [self.reply() for _ in range(int(value))]
        if kind == b'$':
            size = int(value)
            if size < 0: return None
            data = self.file.read(size); self.file.read(2)
            return data
        if kind == b'-': raise RuntimeError('Redis command rejected; details withheld')
        if kind not in (b'+', b':'): raise RuntimeError('Redis response invalid')
        return value

    def command(self, *parts):
        values = [value if isinstance(value, bytes) else str(value).encode() for value in parts]
        self.socket.sendall(b'*' + str(len(values)).encode() + b'\r\n' + b''.join(
            b'$' + str(len(value)).encode() + b'\r\n' + value + b'\r\n' for value in values))
        return self.reply()

    def close(self):
        self.file.close(); self.socket.close()

    def __enter__(self): return self
    def __exit__(self, *args): self.close()

def timestamp(raw, now_ms):
    if not raw: return None
    try:
        # Parse only a primitive timestamp; never deserialize untrusted Java objects.
        if raw[:4] == b'\xac\xed\x00\x05' and b'java.lang.Long' in raw:
            value = struct.unpack('>q', raw[-8:])[0]
        else:
            value = json.loads(raw)
            if isinstance(value, list) and len(value) == 2 and value[0] == 'java.lang.Long': value = value[1]
        return value if type(value) is int and 946684800000 < value <= now_ms + 60000 else None
    except (ValueError, TypeError, struct.error): return None

def memory_sample(client):
    info = dict(line.split(':', 1) for line in client.command('INFO', 'memory').decode().splitlines() if ':' in line)
    used, maximum = int(info['used_memory']), int(info['maxmemory'])
    return {'usedMemoryBytes': used, 'maxMemoryBytes': maximum,
            'usedPercent': round(100 * used / maximum, 2) if maximum > 0 else None,
            'keyCount': int(client.command('DBSIZE'))}

def maintain(client, mode, limit=2000, max_scan=10000, max_seconds=15, idle_seconds=86400):
    if mode not in ('inspect', 'clean') or not 1 <= limit <= 30000: raise ValueError('Invalid maintenance arguments')
    if not 1 <= max_scan <= 100000 or not 1 <= max_seconds <= 60 or idle_seconds < 86400:
        raise ValueError('Maintenance bounds are unsafe')
    now_ms = int(time.time() * 1000); cutoff = now_ms - idle_seconds * 1000
    deadline = time.monotonic() + max_seconds
    report = {'mode': mode, 'scanned': 0, 'eligible': 0, 'removed': 0, 'protected': 0, 'unknownTimestamp': 0}
    cursor = b'0'
    while True:
        cursor, keys = client.command('SCAN', cursor, 'MATCH', SESSION_PATTERN, 'COUNT', 100)
        for key in keys:
            if time.monotonic() >= deadline or report['scanned'] >= max_scan or report['eligible'] >= limit:
                report['boundedStop'] = True; return report | memory_sample(client)
            report['scanned'] += 1
            if client.command('TYPE', key) != b'hash': continue
            raw = client.command('HGETALL', key)
            fields = dict(zip(raw[::2], raw[1::2]))
            if not set(fields).issubset(ALLOWED_FIELDS):
                report['protected'] += 1; continue
            accessed = fields.get(b'lastAccessedTime'); value = timestamp(accessed, now_ms)
            if value is None:
                report['unknownTimestamp'] += 1; continue
            if value >= cutoff: continue
            report['eligible'] += 1
            if mode == 'clean': report['removed'] += int(client.command('EVAL', DELETE_ANONYMOUS, 1, key, accessed))
        if cursor == b'0': return report | {'boundedStop': False} | memory_sample(client)

def main():
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=('inspect', 'clean', 'metrics'))
    parser.add_argument('limit', nargs='?', type=int, default=2000)
    args = parser.parse_args()
    with RedisConnection() as client:
        result = memory_sample(client) if args.mode == 'metrics' else maintain(client, args.mode, args.limit)
    print(json.dumps(result))

if __name__ == '__main__': main()
