"""Test smoke-run safety and evidence parsing without Docker or a live OBS process."""
import json
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import real_obs_smoke as smoke

MARKERS = '\n'.join((
    'Verified incorrect-password authentication rejection with close code 4009',
    'Verified read-only OBS 32.0',
    'Verified Reidentify acknowledgements preserve the live OBS session',
    'Verified disposable scene creation, switching, event delivery, restoration and removal',
))


class RealObsSmokeTests(unittest.TestCase):
    def test_summary_accepts_real_log_prefixes_and_requires_all_checks(self):
        valid = MARKERS + '\n[info] Tests: 0 failed, 0 ignored, 4 total\n'
        smoke.validate_phase_log(valid, 'before-restart')
        for invalid in (valid.replace('0 failed', '1 failed'),
                        valid.replace('0 ignored', '1 ignored'),
                        valid.replace('4 total', '3 total'),
                        valid.replace('Verified read-only OBS ', '')):
            with self.subTest(log=invalid), self.assertRaises(RuntimeError):
                smoke.validate_phase_log(invalid, 'before-restart')

    def test_long_digit_run_near_miss_does_not_mask_later_summary(self):
        # The former unanchored search retried every suffix of this digit run.
        log = '9' * 200_000 + ' failure\n' + MARKERS + '\n0 failed, 0 ignored, 4 total'
        smoke.validate_phase_log(log, 'after-restart')

    def test_malformed_candidate_before_valid_summary_on_same_line(self):
        log = '8 failed, broken ignored, 3 total; prefix0 failed, 0 ignored, 4 total'
        self.assertEqual(smoke.parse_test_summary(log), (0, 0, 4))

    def test_incomplete_and_nondecimal_summaries_are_absent(self):
        for log in ('no summary', ' failed, 0 ignored, 4 total',
                    '0 failed, 0 ignored, 4', '0 failed, ² ignored, 4 total',
                    '0 failed, 0 ignored, broken total'):
            with self.subTest(log=log):
                self.assertIsNone(smoke.parse_test_summary(log))
                phase_log = MARKERS + '\n' + log
                with self.assertRaises(RuntimeError):
                    smoke.validate_phase_log(phase_log, 'before-restart')

    def test_summary_preserves_decimal_unicode_and_first_match(self):
        self.assertEqual(smoke.parse_test_summary('٠ failed, ٠ ignored, ٤ total'), (0, 0, 4))
        self.assertEqual(smoke.parse_test_summary('1 failed, 0 ignored, 4 total; '
                                           '0 failed, 0 ignored, 4 total'), (1, 0, 4))

    def test_container_inspection_rejects_mounts_and_public_bindings(self):
        valid = {'Mounts': [], 'NetworkSettings': {'Ports': {'4455/tcp': [
            {'HostIp': '127.0.0.1', 'HostPort': '12345'}]}}}
        with patch.object(smoke, 'run', return_value=SimpleNamespace(stdout=json.dumps([valid]))):
            self.assertEqual(smoke.inspect_container('owned')[1], 12345)
        for invalid in ({**valid, 'Mounts': [{'Source': '/host'}]},
                        {**valid, 'NetworkSettings': {'Ports': {'4455/tcp': [
                            {'HostIp': '0.0.0.0', 'HostPort': '12345'}]}}}):
            with self.subTest(container=invalid):
                response = SimpleNamespace(stdout=json.dumps([invalid]))
                with patch.object(smoke, 'run', return_value=response), self.assertRaises(RuntimeError):
                    smoke.inspect_container('owned')

    def test_cleanup_removes_container_even_when_log_copy_fails(self):
        with patch.object(smoke.subprocess, 'run', side_effect=[OSError('copy failed'),
                                                              SimpleNamespace(returncode=0)]) as run:
            with self.assertRaises(OSError):
                smoke.remove_container('owned')
        self.assertEqual(run.call_args_list[-1].args[0], ['docker', 'rm', '--force', 'owned'])
