"""Restore a verified dump in a temporary isolated container; never restore over production."""
import argparse
import hashlib
import json
import subprocess
import tarfile
import tempfile
import time
from pathlib import Path
from common import PROJECTS, container, run

def verify(role):
    source=max((Path('/var/backups/nemo-oj')/role).glob('backup-*.tar.gz.gpg'),key=lambda p:p.stat().st_mtime)
    started=time.monotonic();name='nemo-restore-'+role+'-'+str(int(time.time()))
    image=run(['docker','inspect','--format','{{.Config.Image}}',container(role,'mysql' if role=='core' else 'ai-postgres')],stdout=subprocess.PIPE).stdout.decode().strip()
    with tempfile.TemporaryDirectory(prefix='nemo-restore-') as temporary:
        temp=Path(temporary);archive=temp/'backup.tar.gz'
        run(['gpg','--batch','--yes','--pinentry-mode','loopback','--passphrase-file','/etc/nemo-oj/backup-'+role+'.key',
             '--output',str(archive),'--decrypt',str(source)],stdout=subprocess.DEVNULL,timeout=180)
        filename='database.sql' if role=='core' else 'database.dump'
        with tarfile.open(archive) as tar:
            # Read only two known ordinary files, never extract configuration or symlinks.
            for entry in [filename,'manifest.json']:
                member=tar.getmember(entry)
                if not member.isfile() or member.size>512*1024*1024: raise RuntimeError('Invalid archive member')
                with tar.extractfile(member) as stream: (temp/entry).write_bytes(stream.read())
        manifest=json.loads((temp/'manifest.json').read_text())
        if manifest['role']!=role or hashlib.sha256((temp/filename).read_bytes()).hexdigest()!=manifest['dumpSha256']: raise RuntimeError('Dump checksum mismatch')
        args=['docker','run','-d','--name',name,'--label','nemo.restore=yes','--network','none','--memory','512m','--memory-swap','512m','--cpus','1']
        if role=='core': args+=['-e','MYSQL_ALLOW_EMPTY_PASSWORD=yes','-e','MYSQL_DATABASE=nemo_oj',image,'--innodb-buffer-pool-size=64M','--performance-schema=OFF']
        else: args+=['-e','POSTGRES_HOST_AUTH_METHOD=trust','-e','POSTGRES_USER=langgraph','-e','POSTGRES_DB=langgraph',image]
        run(args,stdout=subprocess.DEVNULL,timeout=30)
        try:
            for _ in range(120):
                # The entrypoint's temporary initialization server has no TCP listener.
                probe=['docker','exec',name]+(['mysql','--protocol=TCP','-h127.0.0.1','-uroot','nemo_oj','-e','SELECT 1'] if role=='core' else ['pg_isready','-h127.0.0.1','-U','langgraph'])
                if subprocess.run(probe,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0: break
                time.sleep(1)
            else: raise RuntimeError('Restore container did not become ready')
            with (temp/filename).open('rb') as dump:
                restore=['docker','exec','-i',name]+(['mysql','-uroot','nemo_oj'] if role=='core' else ['pg_restore','--no-owner','--no-privileges','-U','langgraph','-d','langgraph'])
                run(restore,stdin=dump,stdout=subprocess.DEVNULL,timeout=300)
            if role=='core':
                check=['docker','exec',name,'mysql','-uroot','--batch','--skip-column-names','nemo_oj','-e',
                       "SELECT COUNT(*) FROM question_public_number WHERE status='ONLINE'; SELECT COUNT(*) FROM question_submit; SELECT COUNT(*) FROM user; SELECT COUNT(*) FROM schema_migration WHERE version='V18';"]
            else:
                check=['docker','exec',name,'psql','-U','langgraph','-d','langgraph','-tAc',"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public';"]
            counts=run(check,stdout=subprocess.PIPE,timeout=30).stdout.decode().strip().splitlines()
            if not counts or (role=='core' and counts[-1]!='1') or (role=='ai' and int(counts[0])==0): raise RuntimeError('Restored schema validation failed')
            result={'role':role,'backup':source.name,'restored':True,'seconds':round(time.monotonic()-started,2),'counts':counts}
            Path('/var/backups/nemo-oj',role,'restore-verification.json').write_text(json.dumps(result))
            print(json.dumps(result))
        finally: run(['docker','rm','-f','-v',name],stdout=subprocess.DEVNULL,timeout=30)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--role',choices=PROJECTS,required=True)
    try: verify(parser.parse_args().role)
    except Exception as error: raise SystemExit('Restore verification failed: '+type(error).__name__)
