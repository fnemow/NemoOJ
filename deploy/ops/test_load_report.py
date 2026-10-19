import unittest
from load_report import summarize

class SummaryTest(unittest.TestCase):
    def test_expected_failures_are_counted_and_partial_runs_not_promoted(self):
        data={'duration':1200,'interval':4,'submissions':[{'language':'cpp','expected':'compile_error','verdict':'compile_error','seconds':1}], 'summary':{'passed':False}}
        report=summarize(data)
        self.assertFalse(report['passed']);self.assertEqual(report['expectedVerdicts'],1)
        self.assertEqual(report['verdicts'],{'compile_error':1})
    def test_existing_swap_and_oom_are_distinguished_from_measurement_delta(self):
        sample={'availableMiB':1000,'pending':1,'oom':2,'swapIn':100,'swapOut':200,'swapUsedMiB':50,'cpuTicks':[100,0,0,100,0,0,0,0,0,0]}
        last={**sample,'swapIn':101,'cpuTicks':[120,0,0,180,0,0,0,0,0,0]}
        data={'duration':1200,'interval':4,'submissions':[],'resources':[sample,last]}
        report=summarize(data)['resources']
        self.assertEqual(report['oomDelta'],0);self.assertEqual(report['swapOutPages'],0)
        self.assertEqual(report['swapInPages'],1);self.assertAlmostEqual(report['meanSampleCpuPercent'],20)

if __name__=='__main__':unittest.main()
