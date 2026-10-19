import unittest
from mixed_workload import scenario,SCENARIOS
from remote_load import run_completed

class MixedTests(unittest.TestCase):
    def test_every_scenario_covers_all_languages(self):
        for i in range(len(SCENARIOS)):
            cases=[scenario(i+k*len(SCENARIOS)) for k in range(3)]
            self.assertEqual({c[3] for c in cases},{'java','python','cpp'})
            self.assertTrue(all(c[4] for c in cases))
    def test_expected_errors_are_not_false_failures(self):
        d={'submissions':[{'id':'1','expected':'compile_error','verdict':'compile_error'}],
           'expectedSubmissions':1,'burst':1,'dispatchSeconds':1,'duration':300,'summary':{'readErrors':0}}
        self.assertTrue(run_completed(d));d['submissions'][0]['verdict']='accepted';self.assertFalse(run_completed(d))

if __name__=='__main__':unittest.main()
