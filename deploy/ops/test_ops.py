import io
import json
import tempfile
import unittest
import concurrent.futures
import os
from pathlib import Path
from common import atomic_json
from receive import receive
from monitor import transitions

class OperationsTest(unittest.TestCase):
    @unittest.skipIf(os.name=='nt','Host receiver runs on Linux')
    def test_concurrent_heartbeats_never_share_temporary_files(self):
        import contextlib
        with tempfile.TemporaryDirectory() as tmp, contextlib.redirect_stdout(io.StringIO()):
            root=Path(tmp)
            def send(writer):
                for sequence in range(20):
                    message={'role':'ai','issues':{},'writer':writer,'sequence':sequence}
                    receive('ai',io.BytesIO(b'STATUS\n'+json.dumps(message).encode()),root)
            with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
                list(pool.map(send,range(8)))
            state=json.loads((root/'ai/latest-status.json').read_text())
            self.assertEqual(state['sequence'],19)
            self.assertEqual(list((root/'ai').glob('*.tmp')),[])
    @unittest.skipIf(os.name=='nt','Concurrent replace semantics are verified on the Linux deployment hosts')
    def test_concurrent_atomic_writes_never_share_temporary_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'status.json'
            def write(writer):
                for sequence in range(40): atomic_json(path,{'writer':writer,'sequence':sequence})
            with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
                list(pool.map(write,range(8)))
            self.assertEqual(json.loads(path.read_text())['sequence'],39)
            self.assertEqual(list(Path(tmp).glob('*.tmp')),[])

    def test_alert_delay_deduplication_and_recovery(self):
        active,new,recovered=transitions({}, {'queue':'stuck'},100)
        self.assertEqual(new,[])
        active,new,_=transitions(active,{'queue':'stuck'},221)
        self.assertEqual(new,[('queue','stuck')]);active['queue']['notified']=True
        _,new,_=transitions(active,{'queue':'stuck'},500);self.assertEqual(new,[])
        _,_,recovered=transitions(active,{},501);self.assertEqual(recovered,['queue'])

    def test_receiver_cannot_execute_commands_or_replace_recovery_key(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            with self.assertRaises(ValueError): receive('core',io.BytesIO(b'COMMAND\nrm -rf /'),root)
            receive('core',io.BytesIO(b'KEY\n'+b'a'*64+b'\n'),root)
            with self.assertRaises(ValueError): receive('core',io.BytesIO(b'KEY\n'+b'b'*64+b'\n'),root)
            self.assertEqual((root/'core/recovery.key').read_text(),'a'*64+'\n')

    def test_receiver_checks_heartbeat_owner_and_limits(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            with self.assertRaises(ValueError): receive('ai',io.BytesIO(b'STATUS\n'+json.dumps({'role':'core','issues':{}}).encode()),root)
            receive('ai',io.BytesIO(b'STATUS\n'+json.dumps({'role':'ai','issues':{}}).encode()),root)
            self.assertIn('receivedAt',json.loads((root/'ai/latest-status.json').read_text()))
            with self.assertRaises(ValueError): receive('ai',io.BytesIO(b'BACKUP\nshort'),root)
            self.assertEqual(list((root/'ai').glob('*.partial')),[])

if __name__=='__main__': unittest.main()
