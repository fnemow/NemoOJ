import unittest
from unittest.mock import patch
from cloud_acceptance import Api

class LoginBackoffTest(unittest.TestCase):
    @patch('cloud_acceptance.time.sleep')
    def test_sliding_window_backoff_without_changing_limits(self,sleep):
        api=Api();api.csrf={'token':'old'}
        with patch.object(api,'call',side_effect=[RuntimeError('50001: 登录过于频繁（约 158 秒后可重试）'),None]) as call:
            api.login('fixture','in-memory-password')
        sleep.assert_called_once_with(159);self.assertEqual(call.call_count,2)
        self.assertIsNone(api.csrf);self.assertEqual(api.login_wait_seconds,159)
    @patch('cloud_acceptance.time.sleep')
    def test_invalid_credentials_fail_immediately(self,sleep):
        api=Api()
        with patch.object(api,'call',side_effect=RuntimeError('40000: invalid credentials')):
            with self.assertRaises(RuntimeError):api.login('fixture','wrong')
        sleep.assert_not_called()
    @patch('cloud_acceptance.time.sleep')
    def test_persistent_limit_has_bounded_attempts(self,sleep):
        api=Api()
        with patch.object(api,'call',side_effect=RuntimeError('50001: 登录过于频繁（约 300 秒后可重试）')) as call:
            with self.assertRaises(RuntimeError):api.login('fixture','password')
        self.assertEqual(call.call_count,3);self.assertEqual(sleep.call_count,2)

if __name__=='__main__':unittest.main()
