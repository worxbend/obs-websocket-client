"""Generated files may only replace content in the repository's output tree."""
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from check_coverage import manifest_destination
import doc_snippets


class GenerationPathTests(unittest.TestCase):
    def test_valid_nested_output_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.assertEqual(manifest_destination(root, root / 'out/coverage/run.json'),
                             root / 'out/coverage/run.json')
            self.assertEqual(doc_snippets.snippet_destination(root, root / 'out/docs'),
                             root / 'out/docs/DocumentationSnippets.scala')

    def test_output_paths_reject_traversal_and_sibling_prefix(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for destination in (root / 'out/../source', root / 'outside'):
                with self.subTest(destination=destination):
                    with self.assertRaises(ValueError):
                        manifest_destination(root, destination)
                    with self.assertRaises(ValueError):
                        doc_snippets.snippet_destination(root, destination)

    def test_existing_output_symlinks_cannot_escape(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'out').mkdir()
            (root / 'out/linked').symlink_to(root, target_is_directory=True)
            manifest = root / 'out/linked/manifest.json'
            snippets = root / 'out/linked'
            with self.assertRaises(ValueError):
                manifest_destination(root, manifest)
            with self.assertRaises(ValueError):
                doc_snippets.snippet_destination(root, snippets)
            (root / 'out/DocumentationSnippets.scala').symlink_to(root / 'protected.scala')
            destination = root / 'out'
            with self.assertRaises(ValueError):
                doc_snippets.snippet_destination(root, destination)

    def test_snippets_keep_ascii_identifier_recognition(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'docs').mkdir()
            (root / 'README.md').write_text('```scala\nval ascii_2 = 1\n```\n'
                                           '```scala\nval asciié = 2\n```\n'
                                           '```scala\ndef method_2() = 3\n```\n'
                                           '```scala\ndef methodé() = 4\n```\n'
                                           '```scala\nval result\u00a0= 5\n```\n')
            with patch.object(doc_snippets, 'ROOT', root), redirect_stdout(StringIO()):
                doc_snippets.main(root / 'out/docs')
            source = (root / 'out/docs/DocumentationSnippets.scala').read_text()
            self.assertIn('    ascii_2\n', source)
            self.assertIn('    method_2\n', source)
            self.assertIn('    result\n', source)
            self.assertNotIn('    asciié\n', source)
            self.assertNotIn('    methodé\n', source)
