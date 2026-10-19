"""Forced SSH command: accepts only encrypted backups, recovery keys and status; no shell."""
import argparse
import hashlib
import json
import os
import re
import sys
import time
from pathlib import Path
from common import atomic_json

def receive(role, stream, root=Path('/var/lib/nemo-backup')):
    directory=root/role;directory.mkdir(parents=True,exist_ok=True,mode=0o700)
    kind=stream.readline(16).strip()
    if kind==b'KEY':
        value=stream.read(66)
        if not re.fullmatch(b'[0-9a-f]{64}\n',value): raise ValueError('Invalid recovery key')
        target=directory/'recovery.key'
        if target.exists() and target.read_bytes()!=value: raise ValueError('Recovery key rotation requires operator review')
        if not target.exists(): target.write_bytes(value);target.chmod(0o600)
    elif kind==b'STATUS':
        value=stream.read(65537)
        if len(value)>65536: raise ValueError('Status too large')
        data=json.loads(value)
        if data.get('role')!=role or not isinstance(data.get('issues'),dict): raise ValueError('Invalid status')
        data['receivedAt']=time.time()
        atomic_json(directory/'latest-status.json',data)
    elif kind==b'BACKUP':
        temp=directory/('incoming-'+str(os.getpid())+'.partial');total=0;digest=hashlib.sha256()
        try:
            with temp.open('xb') as out:
                temp.chmod(0o600)
                while True:
                    chunk=stream.read(1024*1024)
                    if not chunk: break
                    total+=len(chunk)
                    if total>512*1024*1024: raise ValueError('Backup too large')
                    digest.update(chunk);out.write(chunk)
            if total<64: raise ValueError('Empty backup')
            target=directory/(time.strftime('backup-%Y%m%dT%H%M%SZ',time.gmtime())+'.gpg')
            os.replace(temp,target)
            atomic_json(directory/'last-backup.json',{'receivedAt':time.time(),'bytes':total,'sha256':digest.hexdigest()})
            for file in directory.glob('backup-*.gpg'):
                if not file.is_symlink() and file.stat().st_mtime<time.time()-14*86400: file.unlink()
        finally:
            if temp.exists(): temp.unlink()
    else: raise ValueError('Unsupported operation')
    print('SHA256 '+digest.hexdigest() if kind==b'BACKUP' else 'Accepted '+kind.decode())

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--role',choices=['core','ai'],required=True)
    try: receive(parser.parse_args().role,sys.stdin.buffer)
    except Exception as error: raise SystemExit('Receive failed: '+type(error).__name__)
