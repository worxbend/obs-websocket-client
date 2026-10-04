from html.parser import HTMLParser
from pathlib import Path
import unittest

from build_site import inline, markdown, target


class Tags(HTMLParser):
    def __init__(self, source):
        super().__init__()
        self.tags = []
        self.feed(source)

    def handle_starttag(self, tag, attrs):
        self.tags.append(tag)


class SiteTests(unittest.TestCase):
    def test_compatibility_matrix_has_table_structure(self):
        source = (Path(__file__).resolve().parents[1] / 'docs/compatibility.md').read_text()
        tags = Tags(markdown(source)).tags
        self.assertIn('table', tags)
        self.assertIn('th', tags)
        self.assertIn('td', tags)

    def test_emphasis_preserves_literal_code(self):
        self.assertEqual(inline('**important** and `**literal**`'),
                         '<strong>important</strong> and <code>**literal**</code>')

    def test_links_escape_once_and_preserve_external_markdown(self):
        self.assertEqual(target('https://example.org/doc.md?q=1&x=2'),
                         'https://example.org/doc.md?q=1&x=2')
        self.assertEqual(target('README.md#install'), 'index.html#install')
        self.assertIn('q=1&amp;x=2', inline('[link](https://example.org/?q=1&x=2)'))
        self.assertNotIn('&amp;amp;', inline('[link](https://example.org/?q=1&x=2)'))

    def test_malformed_table_and_fence_fail_explicitly(self):
        for source in ('| A |\n| invalid |', '| A |\n| --- |\n| one | two |', '```scala\nx'):
            with self.subTest(source=source), self.assertRaises(ValueError):
                markdown(source)

    def test_table_at_end_closes_before_returning(self):
        self.assertTrue(markdown('| A |\n| --- |\n| **B** |').endswith('</table></div>'))
