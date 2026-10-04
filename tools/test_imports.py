"""CLI modules must be safe to inspect and reuse without running their commands."""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch


class ImportSafetyTests(unittest.TestCase):
    def test_cli_imports_have_no_external_effects(self):
        for name in ('real_obs_smoke', 'check_generation', 'doc_snippets'):
            with self.subTest(module=name):
                path = Path(__file__).with_name(name + '.py')
                spec = importlib.util.spec_from_file_location(name, path)
                module = importlib.util.module_from_spec(spec)
                with patch('subprocess.run', side_effect=AssertionError('subprocess on import')), \
                     patch('signal.signal', side_effect=AssertionError('signal handler on import')), \
                     patch.object(Path, 'mkdir', side_effect=AssertionError('mkdir on import')), \
                     patch.object(Path, 'unlink', side_effect=AssertionError('unlink on import')), \
                     patch.object(Path, 'write_text', side_effect=AssertionError('write on import')):
                    spec.loader.exec_module(module)
