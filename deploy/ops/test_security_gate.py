from datetime import date
import json
from pathlib import Path
import unittest
from security_gate import ROOT, FORBIDDEN, assess, architecture_violations


class SecurityGateTest(unittest.TestCase):
    def setUp(self):
        self.register = json.loads((ROOT / 'deploy/security/risk-register.json').read_text(encoding='utf-8'))
        self.today = date(2026, 10, 19)
        self.finding = {k:self.register['records'][0][k] for k in ('dependency', 'version', 'id')}
        self.finding['severity'] = 'CRITICAL'

    def test_known_record_remains_visible_and_is_not_called_fixed(self):
        result = assess([self.finding], self.register, self.today, [])
        self.assertTrue(result['passed'])
        self.assertEqual(1, result['rawFindingCount'])
        self.assertEqual(1, result['mitigatedNotFixedCount'])
        self.assertEqual('CRITICAL', result['mitigatedNotFixed'][0]['severity'])

    def test_new_ids_versions_and_even_low_risks_fail(self):
        for key, value in [('id','GHSA-new'), ('version','6.2.18'), ('dependency','different:package')]:
            finding = {**self.finding, key:value, 'severity':'LOW'}
            self.assertFalse(assess([finding], self.register, self.today, [])['passed'])

    def test_expired_review_or_broken_control_cannot_pass(self):
        self.assertFalse(assess([self.finding], self.register, date(2026,11,19), [])['passed'])
        self.assertFalse(assess([self.finding], self.register, self.today, ['Missing guard'])['passed'])

    def test_current_sources_obey_controls(self):
        self.assertEqual([], architecture_violations(ROOT))

    def test_rendering_and_functional_surfaces_are_detected(self):
        for text in ['return FragmentsRendering.with("x");', 'new XsltViewResolver()', 'RouterFunctions.route()', 'ModelAndView result;']:
            self.assertIsNotNone(FORBIDDEN.search(text))
        self.assertIsNone(FORBIDDEN.search('SseEmitter.event().data(jsonPayload)'))


if __name__ == '__main__': unittest.main()
