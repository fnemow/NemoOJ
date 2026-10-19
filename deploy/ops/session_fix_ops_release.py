"""Install bounded session maintenance and add Redis memory monitoring only."""
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path
stage = Path(sys.argv[1]); root = Path('/usr/local/lib/nemo-ops')
sys.path.insert(0, str(root))
from common import atomic_json

candidate = (stage / 'monitor.py').read_text()
begin = candidate.index('        try:\n            from redis_session_ops import RedisConnection, memory_sample')
end = candidate.index('        pending=mysql(', begin)
unchanged = candidate[:begin] + candidate[end:]
unchanged = unchanged.replace("'redis':redis if role=='core' else None,", '')
assert unchanged == (root / 'monitor.py').read_text(), 'Existing monitor differs; review before replacing'

def install(source, destination, mode):
    fd, temporary = tempfile.mkstemp(dir=destination.parent, prefix='.session-fix-')
    os.fchmod(fd, mode)
    with os.fdopen(fd, 'wb') as output: output.write(source.read_bytes()); output.flush(); os.fsync(output.fileno())
    os.replace(temporary, destination)

install(stage / 'redis_session_ops.py', root / 'redis_session_ops.py', 0o600)
install(stage / 'monitor.py', root / 'monitor.py', 0o600)
for name in ('nemo-session-maintenance.service', 'nemo-session-maintenance.timer'):
    install(stage / name, Path('/etc/systemd/system') / name, 0o644)
for command in (['systemctl', 'daemon-reload'], ['systemctl', 'enable', '--now', 'nemo-session-maintenance.timer']):
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20)
    assert result.returncode == 0, 'Session maintenance installation failed'
report = {'passed': True, 'redisMemoryWarningPercent': 80, 'cleanupIntervalMinutes': 15,
          'cleanupLimit': 2000, 'anonymousIdleMinimumHours': 24, 'backupsCreated': False}
atomic_json(stage / 'ops-release.json', report); print(json.dumps(report))
