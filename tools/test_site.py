from html.parser import HTMLParser
from pathlib import Path
from hashlib import sha256
import re
import unittest

from build_site import ROOT, code_html, inline, markdown, target


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

    def test_nested_pages_resolve_links_from_their_source_directory(self):
        page = 'guides/read-version-and-scenes'
        self.assertEqual(target('../architecture.md#requests-and-event-dispatch', page),
                         'architecture.html#requests-and-event-dispatch')
        self.assertEqual(target('../../README.md', page),
                         'https://github.com/worxbend/obs-websocket-client/blob/main/README.md')
        self.assertEqual(target('../decisions/001-library-boundaries.md', page),
                         'decisions/001-library-boundaries.html')
        self.assertEqual(target('#running-the-examples', page),
                         'guides/read-version-and-scenes.html#running-the-examples')

    def test_companion_source_is_embedded_verbatim_and_scoped(self):
        path = 'examples/src/com/worxbend/obs/websocket/client/examples/Quickstart.scala'
        result = code_html('scala file=' + path, [])
        self.assertIn('object Quickstart:', result)
        self.assertIn('def discover(session: ObsSession)', result)
        for invalid in ('../outside.scala', 'core/src/Other.scala', 'examples/src/../../build.mill'):
            with self.subTest(path=invalid), self.assertRaises(ValueError):
                code_html('scala file=' + invalid, [])
        with self.assertRaises(ValueError):
            code_html('scala file=' + path, ['unverified duplicate'])

    def test_architecture_diagrams_match_sources_and_reject_stale_assets(self):
        source = (ROOT / 'docs/architecture.md').read_text()
        diagrams = re.findall(r'```mermaid\n(.*?)\n```', source, re.S)
        self.assertEqual(len(diagrams), 5)
        for diagram in diagrams:
            with self.subTest(digest=sha256(diagram.encode()).hexdigest()):
                self.assertIn('<img src="assets/architecture/', code_html('mermaid', diagram.splitlines()))
                changed_lines = (diagram + '\n%% changed').splitlines()
                with self.assertRaises(ValueError):
                    code_html('mermaid', changed_lines)
        with self.assertRaises(ValueError):
            code_html('mermaid', ['%% asset: ../outside.svg', 'flowchart LR'])

    def test_frontmatter_renders_title_without_metadata(self):
        result = markdown('---\nid: read-scenes\ntitle: "Read scenes"\n---\n\nRead OBS.')
        self.assertIn('<h1>Read scenes</h1>', result)
        self.assertNotIn('id: read-scenes', result)
        with self.assertRaises(ValueError):
            markdown('---\nid: missing-title\n---\nBody')

    def test_heading_requires_space_and_levels_clamp_to_six(self):
        result = markdown('#tag stays text\n####### Deep')
        self.assertIn('<p>#tag stays text</p>', result)
        self.assertIn('<h6 id="deep">Deep</h6>', result)
        self.assertNotIn('<h7', result)

    def test_link_target_allows_one_level_of_nested_parentheses(self):
        url = 'https://en.wikipedia.org/wiki/OBS_(disambiguation)'
        self.assertIn(f'href="{url}"', inline(f'[OBS]({url})'))
