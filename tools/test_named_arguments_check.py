"""Safety checks for the explicit Mill integration runner; these never invoke Mill."""
from pathlib import Path
import subprocess
import tempfile
import unittest

from check_named_arguments import prepare_fixture, require_rewrite_diagnostic, require_sources


class NamedArgumentCheckTests(unittest.TestCase):
    def test_scratch_reset_keeps_other_output_and_uses_versioned_sources(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            resources = root / 'namedArgumentRules/test/resources'
            resources.mkdir(parents=True)
            (resources / 'input.scala.txt').write_bytes(b'input')
            (resources / 'expected.scala.txt').write_bytes(b'expected')
            (resources / 'support.scala.txt').write_bytes(b'support')
            scratch = root / 'out/named-argument-fixture'
            scratch.mkdir(parents=True)
            (scratch / 'Stale.scala').write_bytes(b'stale')
            sibling = root / 'out/keep.txt'
            sibling.write_bytes(b'keep')
            prepared, original, expected = prepare_fixture(root)
            self.assertEqual(prepared, scratch)
            self.assertEqual(original, {'Fixture.scala': b'input', 'Support.scala': b'support'})
            self.assertEqual(expected, {'Fixture.scala': b'expected', 'Support.scala': b'support'})
            self.assertEqual({path.name for path in scratch.iterdir()}, set(original))
            require_sources(scratch, original, 'Prepared fixture differs')
            (scratch / 'Support.scala').write_bytes(b'changed')
            with self.assertRaises(RuntimeError):
                require_sources(scratch, original, 'Support must be preserved')
            self.assertEqual(sibling.read_bytes(), b'keep')

    def test_symlinked_scratch_is_rejected_before_deleting_contents(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            resources = root / 'namedArgumentRules/test/resources'
            resources.mkdir(parents=True)
            (resources / 'input.scala.txt').write_bytes(b'input')
            (resources / 'expected.scala.txt').write_bytes(b'expected')
            (resources / 'support.scala.txt').write_bytes(b'support')
            (root / 'out').mkdir()
            (root / 'out/named-argument-fixture').symlink_to(resources, target_is_directory=True)
            with self.assertRaises(ValueError):
                prepare_fixture(root)
            self.assertEqual((resources / 'input.scala.txt').read_bytes(), b'input')

    def test_only_actual_fixture_diff_counts_as_expected_failure(self):
        diff = '138] --- a/Fixture.scala\n138] +++ b/Fixture.scala\n138] @@ -1 +1 @@\n-old\n+new\n'
        result = subprocess.CompletedProcess([], 1, stdout=diff)
        require_rewrite_diagnostic(result)
        for code, diagnostic in ((0, diff), (1, 'compiler error in Fixture.scala'),
                                 (1, 'download failed'), (1, diff.replace('Fixture.scala', 'Other.scala'))):
            result = subprocess.CompletedProcess([], code, stdout=diagnostic)
            with self.subTest(code=code, diagnostic=diagnostic), self.assertRaises(RuntimeError):
                require_rewrite_diagnostic(result)
