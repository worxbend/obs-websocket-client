"""Fail-closed test-runner behavior, exercised in isolated child interpreters."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

RUNNER = Path(__file__).with_name('run_codegen_tests.py').resolve()


class CodegenRunnerTests(unittest.TestCase):
    def execute(self, root, body):
        tests = root / 'codegen/test/src'
        tests.mkdir(parents=True)
        (root / 'codegen/src').mkdir(parents=True)
        (tests / 'test_fixture.py').write_text(body)
        code = ('import importlib.util, pathlib; '
                f'spec = importlib.util.spec_from_file_location("runner", {str(RUNNER)!r}); '
                'runner = importlib.util.module_from_spec(spec); spec.loader.exec_module(runner); '
                f'runner.ROOT = pathlib.Path({str(root)!r}); '
                'raise SystemExit(runner.run(runner.ROOT / "out"))')
        return subprocess.run([sys.executable, '-c', code], cwd=root,
                              capture_output=True, text=True)

    def test_discovery_requires_nonempty_suite(self):
        with tempfile.TemporaryDirectory() as directory:
            result = self.execute(Path(directory), '# no tests\n')
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('No codegen tests discovered', result.stderr)

    def test_success_and_failure_reports_are_real(self):
        for expression, expected in [('True', 0), ('False', 1)]:
            with self.subTest(expression=expression), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                result = self.execute(root, 'import unittest\nclass Example(unittest.TestCase):\n'
                                      f'    def test_case(self): self.assertTrue({expression})\n')
                self.assertEqual(result.returncode, expected, result.stderr)
                report = json.loads((root / 'out/tests.json').read_text())
                self.assertEqual(report['tests'], 1)
                self.assertEqual(report['failures'], expected)

    def test_skipped_test_is_not_success(self):
        with tempfile.TemporaryDirectory() as directory:
            result = self.execute(Path(directory), 'import unittest\nclass Example(unittest.TestCase):\n'
                                  '    @unittest.skip("missing coverage")\n'
                                  '    def test_case(self): pass\n')
            self.assertNotEqual(result.returncode, 0)
