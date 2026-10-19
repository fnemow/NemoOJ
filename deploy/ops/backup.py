"""Encrypted, verified local backups and restricted off-host replication."""
import argparse
import hashlib
import json
import os
import secrets
import subprocess
import tarfile
import tempfile
import time
from pathlib import Path
from common import PROJECTS, atomic_json, container, run, send_remote

def backup(role):
    import fcntl
    root=Path(PROJECTS[role][0]);directory=Path('/var/backups/nemo-oj')/role
    directory.mkdir(parents=True,exist_ok=True,mode=0o700);directory.chmod(0o700)
    with (directory/'backup.lock').open('w') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        for leftover in directory.glob('backup-*.tar.gz.partial'):
            if leftover.is_file() and not leftover.is_symlink() and leftover.stat().st_mtime<time.time()-86400: leftover.unlink()
        key=Path('/etc/nemo-oj')/('backup-'+role+'.key')
        if not key.exists(): key.write_text(secrets.token_hex(32)+'\n');key.chmod(0o600)
        # The peer stores this recovery key separately from the encrypted archive.
        send_remote(b'KEY',key)
        stamp=time.strftime('%Y%m%dT%H%M%SZ',time.gmtime());destination=directory/('backup-'+stamp+'.tar.gz.gpg')
        with tempfile.TemporaryDirectory(prefix='nemo-backup-',dir=directory) as temporary:
            temp=Path(temporary);dump=temp/('database.sql' if role=='core' else 'database.dump')
            with dump.open('wb') as output:
                if role=='core':
                    run(['docker','exec',container(role,'mysql'),'sh','-c',
                         'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -uroot --single-transaction --no-tablespaces --routines --triggers --events --set-gtid-purged=OFF nemo_oj'],stdout=output,timeout=300)
                else:
                    run(['docker','exec',container(role,'ai-postgres'),'sh','-c',
                         'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc'],stdout=output,timeout=300)
            (temp/'manifest.json').write_text(json.dumps({'role':role,'timestamp':stamp,'dumpSha256':hashlib.sha256(dump.read_bytes()).hexdigest()}))
            archive=temp/'backup.tar.gz'
            files=['.env','docker-compose.yml','compose.core-cloud.yml','compose.core-4g-preview.yml','nginx','haproxy','migration'] if role=='core' else ['.env.agent','compose.agent-cloud.yml']
            with tarfile.open(archive,'w:gz') as tar:
                tar.add(dump,arcname=dump.name);tar.add(temp/'manifest.json',arcname='manifest.json')
                for name in files:
                    path=root/'deploy'/name
                    if path.exists(): tar.add(path,arcname='config/'+name)
            partial=destination.with_suffix('.partial')
            run(['gpg','--batch','--yes','--pinentry-mode','loopback','--passphrase-file',str(key),
                 '--symmetric','--cipher-algo','AES256','--output',str(partial),str(archive)],stdout=subprocess.DEVNULL,timeout=300)
            # Exercise authenticated decryption and validate the tar stream before publishing.
            checked=temp/'checked.tar.gz'
            run(['gpg','--batch','--yes','--pinentry-mode','loopback','--passphrase-file',str(key),
                 '--output',str(checked),'--decrypt',str(partial)],stdout=subprocess.DEVNULL,timeout=300)
            if hashlib.sha256(checked.read_bytes()).digest()!=hashlib.sha256(archive.read_bytes()).digest(): raise RuntimeError('Backup verification failed')
            partial.chmod(0o600);os.replace(partial,destination)
        send_remote(b'BACKUP',destination)
        atomic_json(directory/'status.json',{'successAt':time.time(),'name':destination.name,'bytes':destination.stat().st_size,
                                            'sha256':hashlib.sha256(destination.read_bytes()).hexdigest(),'offHostVerified':True})
        for file in directory.glob('backup-*.tar.gz.gpg'):
            if file.is_file() and not file.is_symlink() and file.stat().st_mtime < time.time()-7*86400: file.unlink()
        print(json.dumps({'role':role,'backup':destination.name,'offHostVerified':True}))

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--role',choices=PROJECTS,required=True)
    try: backup(parser.parse_args().role)
    except Exception as error: raise SystemExit('Backup failed: '+type(error).__name__)
