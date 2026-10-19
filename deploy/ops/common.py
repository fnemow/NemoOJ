"""Host-only operations helpers. Never print subprocess output or environment secrets."""
import json
import hashlib
import os
import subprocess
import tempfile
from pathlib import Path

PROJECTS = {'core': ('/srv/nemo-oj', 'nemo-oj'), 'ai': ('/srv/nemo-ai', 'nemo-ai')}

def run(args, **kwargs):
    return subprocess.run(args, check=True, stderr=subprocess.PIPE, **kwargs)

def container(role, service):
    project = PROJECTS[role][1]
    value = run(['docker', 'ps', '-q', '--filter', 'label=com.docker.compose.project='+project,
                 '--filter', 'label=com.docker.compose.service='+service], stdout=subprocess.PIPE).stdout.decode().strip()
    if not value or '\n' in value: raise RuntimeError('Expected exactly one running '+service)
    return value

def mysql(sql):
    return run(['docker','exec','-i',container('core','mysql'),'sh','-c',
                'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --batch --skip-column-names nemo_oj'],
               input=sql.encode(),stdout=subprocess.PIPE,timeout=30).stdout.decode().strip()

def env_file(path):
    values={}
    for line in Path(path).read_text().splitlines():
        if not line.strip() or line.lstrip().startswith('#') or '=' not in line: continue
        key,value=line.split('=',1);value=value.strip()
        if len(value)>1 and value[0]==value[-1] and value[0] in "\"'": value=value[1:-1]
        values[key.strip()]=value
    return values

def settings():
    return json.loads(Path('/etc/nemo-oj/ops.json').read_text())

def send_remote(prefix, source):
    config=settings()
    args=['ssh','-T','-o','BatchMode=yes','-o','ConnectTimeout=10','-o','ServerAliveInterval=15','-o','ServerAliveCountMax=3','-o','StrictHostKeyChecking=yes',
          '-o','UserKnownHostsFile=/etc/nemo-oj/backup_known_hosts','-i','/etc/nemo-oj/backup_ssh',
          'nemo-backup@'+config['peer']]
    with subprocess.Popen(args,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE) as process:
        digest=hashlib.sha256()
        process.stdin.write(prefix+b'\n')
        if isinstance(source,bytes): process.stdin.write(source);digest.update(source)
        else:
            with Path(source).open('rb') as stream:
                while True:
                    chunk=stream.read(1024*1024)
                    if not chunk: break
                    digest.update(chunk)
                    process.stdin.write(chunk)
        process.stdin.close();process.stdin=None
        reply,_ = process.communicate(timeout=180)
        if process.returncode: raise RuntimeError('Off-host transfer failed')
        if prefix==b'BACKUP' and reply.strip()!=('SHA256 '+digest.hexdigest()).encode(): raise RuntimeError('Off-host checksum mismatch')

def atomic_json(path, data):
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True,mode=0o700)
    fd,name=tempfile.mkstemp(prefix=path.name+'.',suffix='.tmp',dir=path.parent)
    temporary=Path(name)
    try:
        with os.fdopen(fd,'w',encoding='utf-8') as output:
            json.dump(data,output);output.flush();os.fsync(output.fileno())
        temporary.chmod(0o600);os.replace(temporary,path)
    finally:
        try: temporary.unlink()
        except FileNotFoundError: pass
