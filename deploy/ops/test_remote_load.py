"""Acceptance must fail closed for partial runs, duplicates, retries or backlog."""
import copy
import unittest
from remote_load import run_completed, final_drained

class LoadEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.data={'submissions':[{'id':'1','verdict':'accepted'}], 'expectedSubmissions':1,
                   'burst':0,'dispatchSeconds':900,'duration':900,'summary':{'readErrors':0}}
        self.final={'pending':0,'fixturesRemoved':True,'residual':0,
                    'attempts':'1\t1\t2','queue':'name\tmessages_ready\tmessages_unacknowledged\njudge.queue\t0\t0\n'}
    def test_complete_run_and_drained_header(self):
        self.assertTrue(run_completed(self.data));self.assertTrue(final_drained(self.final,1))
    def test_short_or_missing_run_rejected(self):
        for field,value in [('dispatchSeconds',240),('expectedSubmissions',2),('monitorError','probe failure')]:
            d=copy.deepcopy(self.data);d[field]=value;self.assertFalse(run_completed(d))
    def test_transient_probe_failure_does_not_invalidate_complete_run(self):
        self.data['probeFailures']=[{'error':'exit 255'}];self.assertTrue(run_completed(self.data))
    def test_verdict_and_read_error_rejected(self):
        self.data['submissions'][0]['verdict']='time_limit_exceeded';self.assertFalse(run_completed(self.data))
        self.data['submissions'][0]['verdict']='accepted';self.data['summary']['readErrors']=1;self.assertFalse(run_completed(self.data))
    def test_duplicate_submission_rejected(self):
        self.data['submissions']*=2;self.data['expectedSubmissions']=2;self.assertFalse(run_completed(self.data))
    def test_mixed_expected_errors_complete_without_relaxing_attempt_count(self):
        self.final['attempts']='1\t1\t3'
        self.assertFalse(final_drained(self.final,1))
        self.assertTrue(final_drained(self.final,1,allow_errors=True))
        self.data['submissions'][0].update(expected='compile_error',verdict='compile_error')
        self.assertTrue(run_completed(self.data))
        self.final['attempts']='1\t2\t3'
        self.assertFalse(final_drained(self.final,1,allow_errors=True))
    def test_backlog_missing_results_and_retries_rejected(self):
        for field,value in [('pending',1),('fixturesRemoved',False),('residual',1),
                            ('attempts','1\t2\t2'),('attempts',''),('queue','judge.queue\t0\t1'),('queue','')]:
            d=copy.deepcopy(self.final);d[field]=value;self.assertFalse(final_drained(d,1))
    def test_burst_completes_without_fifteen_minute_wait(self):
        self.data.update(burst=1,dispatchSeconds=.01);self.assertTrue(run_completed(self.data))

if __name__=='__main__':unittest.main()
