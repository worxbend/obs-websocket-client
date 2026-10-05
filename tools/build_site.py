#!/usr/bin/env python3
"""Build and validate the offline documentation site using only Python's standard library."""
from html import escape
from html.parser import HTMLParser
from pathlib import Path
from hashlib import sha256
import json
import posixpath
import re
import shutil
import sys
from urllib.parse import unquote, urlparse
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'out/site'
BASE = '/obs-websocket-client/'
HTML_SUFFIX = '.html'
LIST_END = '</ul>'
GUIDES = [('README', 'Home'), ('quickstart', 'Getting started'),
          ('guides/read-version-and-scenes', 'Read version and scenes'),
          ('architecture', 'Architecture'), ('decisions', 'Architecture decisions'),
          ('decisions/001-library-boundaries', 'ADR 001: Library boundaries'),
          ('decisions/002-scoped-session-and-reconnect', 'ADR 002: Session ownership'),
          ('decisions/003-pinned-protocol-generation', 'ADR 003: Protocol generation'),
          ('decisions/004-additive-peer-features', 'ADR 004: Peer-inspired features'),
          ('feature-expansion', 'Feature comparison'),
          ('requests', 'Requests'),
          ('events', 'Events'), ('recipes', 'Recipes'), ('compatibility', 'Compatibility'), ('server', 'HTTP sample'),
          ('contributing', 'Contributing'), ('code-generation', 'Code generation'), ('releases', 'Releases')]


def target(link, page='README'):
    parsed = urlparse(link)
    if parsed.scheme or parsed.netloc:
        return link
    if not parsed.path:
        filename = ('index' if page == 'README' else page) + HTML_SUFFIX
        return parsed._replace(path=filename).geturl() if parsed.fragment else link
    if parsed.path.startswith('/'):
        return link
    path = posixpath.normpath(posixpath.join('docs', posixpath.dirname(page), parsed.path))
    pages = {f'docs/{name}.md': ('index' if name == 'README' else name) + HTML_SUFFIX
             for name, _ in GUIDES}
    if path in pages:
        path = pages[path]
    elif not path.startswith('assets/'):
        path = 'https://github.com/worxbend/obs-websocket-client/blob/main/' + path
    return parsed._replace(path=path).geturl()


INLINE_PATTERNS = (
    ('code', re.compile(r'`([^`]+)`')),
    ('link', re.compile(r'\[([^\]]+)\]\(((?:[^()]|\([^()]*\))+)\)')),
    ('strong', re.compile(r'\*\*(.+?)\*\*')),
)


def inline_tokens(text):
    """Consume the earliest complete construct, so code and link contents stay intact."""
    matches = {kind: pattern.search(text) for kind, pattern in INLINE_PATTERNS}
    while candidates := [(kind, match) for kind, match in matches.items() if match is not None]:
        kind, match = min(candidates, key=lambda candidate: candidate[1].start())
        yield kind, match
        position = match.end()
        for token_kind, pattern in INLINE_PATTERNS:
            pending = matches[token_kind]
            if pending is not None and pending.start() < position:
                matches[token_kind] = pattern.search(text, position)


def inline(text, page='README'):
    rendered, end = [], 0
    for kind, match in inline_tokens(text):
        rendered.append(escape(text[end:match.start()]))
        if kind == 'code':
            code = match[1]
            rendered.append('<code>' + escape(code) + '</code>')
        elif kind == 'link':
            label, link = match.groups()
            rendered.append(f'<a href="{escape(target(link, page), quote=True)}">{inline(label, page)}</a>')
        else:
            rendered.append('<strong>' + inline(match[1], page) + '</strong>')
        end = match.end()
    rendered.append(escape(text[end:]))
    return ''.join(rendered)


def table_html(lines, page='README'):
    rows = [[cell.strip() for cell in line.strip().strip('|').split('|')] for line in lines]
    if len(rows) < 2 or not all(re.fullmatch(r':?-+:?', cell) for cell in rows[1]):
        raise ValueError('Malformed Markdown table separator')
    width = len(rows[0])
    if any(len(row) != width for row in rows):
        raise ValueError('Inconsistent Markdown table columns')
    def row_html(row, tag):
        return '<tr>' + ''.join(f'<{tag}>{inline(cell, page)}</{tag}>' for cell in row) + '</tr>'
    return ('<div class="table-scroll"><table><thead>' + row_html(rows[0], 'th') +
            '</thead><tbody>' + ''.join(row_html(row, 'td') for row in rows[2:]) +
            '</tbody></table></div>')


def code_html(language, lines):
    source = '\n'.join(lines)
    if language == 'mermaid':
        asset = re.match(r'%% asset: ([a-z0-9-]+\.svg)\n', source)
        if not asset:
            raise ValueError('Mermaid fence needs a first-line %% asset: filename.svg comment')
        name = asset[1]
        svg = (ROOT / 'assets/architecture' / name).read_text()
        digest = sha256(source.encode()).hexdigest()
        if f'data-source-sha256="{digest}"' not in svg:
            raise ValueError(f'Stale Mermaid SVG: {name}; render the current fence before building')
        return (f'<figure class="diagram"><img src="assets/architecture/{name}" '
                f'alt="{escape(name[:-4].replace("-", " ").capitalize())} diagram"></figure>')
    if language.startswith('scala file='):
        relative = language.removeprefix('scala file=')
        path = (ROOT / relative).resolve()
        if lines or not path.is_relative_to((ROOT / 'examples/src').resolve()) or path.suffix != '.scala':
            raise ValueError('Source embeds must be empty fences pointing to examples/src Scala files')
        source = path.read_text()
    return '<pre><button class="copy" aria-label="Copy code">Copy</button><code>' + escape(source) + '</code></pre>'


class MarkdownRenderer:
    """State of one document; block transitions own their pending content."""
    def __init__(self, page):
        self.page = page
        self.result = []
        self.paragraph = []
        self.table = []
        self.code = None
        self.language = ''
        self.in_list = False

    def flush_paragraph(self):
        if self.paragraph:
            self.result.append('<p>' + inline(' '.join(self.paragraph), self.page) + '</p>')
            self.paragraph.clear()

    def flush_table(self):
        if self.table:
            self.result.append(table_html(self.table, self.page))
            self.table.clear()

    def close_list(self):
        if self.in_list:
            self.result.append(LIST_END)
            self.in_list = False

    def fence(self, line):
        self.flush_paragraph()
        if self.code is None:
            self.code = []
            self.language = line[3:].strip()
        else:
            self.result.append(code_html(self.language, self.code))
            self.code = None

    def heading(self, line):
        level = len(line) - len(line.lstrip('#'))
        if level == 0 or line[level:level + 1] != ' ':
            return False
        self.flush_paragraph()
        title = line[level:].strip()
        anchor = re.sub(r'[^a-z0-9]+', '-', title.lower()).strip('-')
        self.result.append(f'<h{min(level, 6)} id="{anchor}">{inline(title, self.page)}</h{min(level, 6)}>')
        return True

    def prose(self, line):
        if not line.startswith('- '):
            self.close_list()
        if self.heading(line):
            return
        if line.startswith('- '):
            self.flush_paragraph()
            if not self.in_list:
                self.result.append('<ul>')
                self.in_list = True
            self.result.append('<li>' + inline(line[2:], self.page) + '</li>')
        elif not line:
            self.flush_paragraph()
        else:
            self.paragraph.append(line)

    def line(self, line):
        if self.code is None and line.startswith('|'):
            self.flush_paragraph()
            self.close_list()
            self.table.append(line)
            return
        self.flush_table()
        if line.startswith('```'):
            self.fence(line)
        elif self.code is not None:
            self.code.append(line)
        else:
            self.prose(line)

    def render(self, source):
        if source.startswith('---\n'):
            metadata, separator, source = source[4:].partition('\n---\n')
            title = re.search(r'^title: "(.+)"$', metadata, re.M)
            if not separator or not title:
                raise ValueError('Guide frontmatter needs a quoted title and closing separator')
            self.result.append('<h1>' + escape(title[1]) + '</h1>')
        for line in source.splitlines():
            self.line(line)
        self.flush_paragraph()
        self.flush_table()
        self.close_list()
        if self.code is not None:
            raise ValueError('Unclosed code fence')
        return '\n'.join(self.result)


def markdown(source, page='README'):
    # Support the constructs used by the offline guides; fail on malformed tables/fences.
    return MarkdownRenderer(page).render(source)


CSS = '''
.diagram{margin:1.5rem 0;overflow-x:auto;background:#fff;border-radius:10px;padding:1rem}.diagram img{display:block;width:100%;min-width:620px;height:auto}
.table-scroll{overflow-x:auto}table{border-collapse:collapse;width:100%;margin:1rem 0}th,td{border:1px solid var(--line);padding:.5rem .8rem;text-align:left}th{background:var(--panel)}
:root{color-scheme:light dark;--bg:#f5f7fa;--text:#132333;--panel:#fff;--accent:#006b54;--line:#d9e2e9}
[data-theme=dark]{--bg:#0e1622;--text:#e7f1f8;--panel:#182332;--accent:#70f0be;--line:#344351}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:17px/1.7 system-ui,sans-serif}
a{color:var(--accent);text-underline-offset:.2em}a:focus-visible,button:focus-visible,input:focus-visible,select:focus-visible{outline:3px solid #e38039;outline-offset:4px}
header{display:flex;align-items:center;gap:1rem;padding:1rem 4vw;border-bottom:1px solid var(--line);flex-wrap:wrap}header img{width:42px;height:42px}.brand{font-weight:750;text-decoration:none;font-size:1.1rem}header label{margin-left:auto}
button,select,input{font:inherit;padding:.35rem .6rem;border:1px solid var(--line);border-radius:6px;background:var(--panel);color:var(--text)}button{cursor:pointer}
.layout{display:grid;grid-template-columns:230px minmax(0,850px);gap:3vw;max-width:1240px;margin:2rem auto;padding:0 3vw}nav a{display:block;padding:.35rem .7rem;border-radius:6px}nav a[aria-current=page]{background:var(--panel);font-weight:700}nav input{width:100%;margin-bottom:1rem}main{min-width:0}h1{font-size:clamp(2.1rem,4vw,3.2rem);line-height:1.15;letter-spacing:-.04em}h2{margin-top:2.2rem;line-height:1.3}p,li{max-width:76ch}pre{position:relative;background:var(--panel);padding:1.5rem;border:1px solid var(--line);border-radius:10px;overflow:auto;font-size:.86rem;line-height:1.6}code{font-family:ui-monospace,monospace}p code,li code{background:var(--panel);padding:.1em .25em}.copy{position:absolute;right:.4rem;top:.4rem;font-size:.7rem}footer{padding:2rem 4vw;border-top:1px solid var(--line);font-size:.85rem}.status{border-left:4px solid var(--accent);padding:.6rem 1rem;background:var(--panel)}.skip{position:absolute;top:-100px}.skip:focus{top:0;background:var(--panel);z-index:2}.search-results a{font-size:.9rem}.banner{width:100%;border-radius:12px}@media(max-width:760px){.layout{display:block}nav{margin-bottom:2rem}nav .links{display:flex;flex-wrap:wrap}nav a{padding:.3rem .6rem}header label{margin-left:0}}
'''
JS = '''
const theme=document.querySelector('#theme');
const applyTheme=value=>document.documentElement.dataset.theme=value;
applyTheme(localStorage.getItem('theme')||(matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light'));
theme.addEventListener('click',()=>{const next=document.documentElement.dataset.theme==='dark'?'light':'dark';applyTheme(next);localStorage.setItem('theme',next)});
document.querySelectorAll('.copy').forEach(button=>button.addEventListener('click',async()=>{try{await navigator.clipboard.writeText(button.nextElementSibling.textContent);button.textContent='Copied'}catch{button.textContent='Select to copy'}}));
let pages=[];fetch('search.json').then(r=>r.json()).then(data=>pages=data);
document.querySelector('#search').addEventListener('input',event=>{const query=event.target.value.trim().toLowerCase();const results=document.querySelector('#results');results.replaceChildren();if(query.length<2)return;pages.filter(p=>p.text.toLowerCase().includes(query)).forEach(page=>{const a=document.createElement('a');a.href=page.url;a.textContent=page.title;results.append(a)})});
'''


def layout(title, name, body, filename=None):
    filename = filename or ('index' if name == 'README' else name) + HTML_SUFFIX
    nav = ''.join(f'<a href="{("index" if slug == "README" else slug)}.html"' +
                  (' aria-current="page"' if slug == name else '') + f'>{label}</a>' for slug, label in GUIDES)
    api = ''.join(f'<a href="api/{module}/index.html">{module} API</a>' for module in ('protocol', 'core', 'sttp'))
    return f'''<!doctype html><html lang="en"><head><base href="{BASE}"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>{escape(title)} · OBS WebSocket Client</title><meta name="description" content="Typed, scoped OBS Studio control with Scala 3, Java 25, and Ox."><meta property="og:image" content="https://worxbend.github.io{BASE}assets/banner.svg"><link rel="icon" href="assets/logo.svg" type="image/svg+xml"><link rel="stylesheet" href="style.css"><script defer src="site.js"></script></head><body><a class="skip" href="{escape(filename)}#content">Skip to content</a><header><img src="assets/logo.svg" alt=""><a class="brand" href="index.html">OBS / Scala</a><label for="version">Version</label><select id="version" aria-label="Documentation version"><option>Development · unreleased</option></select><button id="theme" aria-label="Toggle color theme">Light / dark</button></header><div class="layout"><nav aria-label="Documentation"><label for="search">Search guides</label><input id="search" type="search" placeholder="Requests, events, setup…"><div id="results" class="search-results" aria-live="polite"></div><div class="links">{nav}{api}</div></nav><main id="content"><p class="status">Development documentation. No public release yet.</p>{body}</main></div><footer>MIT · Independent community client · <a href="https://github.com/worxbend/obs-websocket-client/blob/main/docs/{name}.md">Edit this page</a> · <a href="https://github.com/worxbend/obs-websocket-client">Source</a></footer></body></html>'''


class Links(HTMLParser):
    def __init__(self):
        super().__init__()
        self.links = []
    def handle_starttag(self, tag, attrs):
        for key, value in attrs:
            if key in {'href', 'src'} and value:
                self.links.append(value)


def local_link_error(path, link, uses_site_base):
    parsed = urlparse(link)
    if parsed.scheme or link.startswith('#'):
        return None
    relative = unquote(parsed.path)
    if relative.startswith('/'):
        if not relative.startswith(BASE):
            return f'{path.name}: escapes repository base path: {link}'
        destination = OUT / relative[len(BASE):]
    else:
        # Guides use the site base; Scaladoc retains relative asset locations.
        destination = (OUT if uses_site_base else path.parent) / relative
    if not destination.exists():
        return f'{path.name}: missing {link}'
    return None


def check_links():
    errors = []
    for path in OUT.rglob('*.html'):
        parser = Links()
        source = path.read_text()
        parser.feed(source)
        uses_site_base = f'<base href="{BASE}">' in source
        errors.extend(error for link in parser.links
                      if (error := local_link_error(path, link, uses_site_base)) is not None)
    if errors:
        raise ValueError('\n'.join(errors))


def doc_jars(argv):
    jars = {}
    for argument in argv:
        module, separator, path = argument.partition('=')
        if not separator:
            raise SystemExit(f'Expected module=docJar-path arguments, got {argument!r}')
        jars[module] = Path(path)
    expected = {'protocol', 'core', 'sttp'}
    if set(jars) != expected:
        raise SystemExit(f'Expected docJar paths for {sorted(expected)}, got {sorted(jars)}')
    for module, path in jars.items():
        if not path.is_file():
            raise FileNotFoundError(f'{module}: {path} (run the module docJar task first)')
    return jars


def main(argv):
    jars = doc_jars(argv)
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True)
    shutil.copytree(ROOT / 'assets', OUT / 'assets')
    search = []
    for name, title in GUIDES:
        source = (ROOT / 'docs' / f'{name}.md').read_text()
        filename = ('index' if name == 'README' else name) + HTML_SUFFIX
        destination = OUT / filename
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(layout(title, name, markdown(source, name)))
        search.append({'title': title, 'url': filename, 'text': source})
    for module in ('protocol', 'core', 'sttp'):
        target = OUT / 'api' / module
        with ZipFile(jars[module]) as archive:
            archive.extractall(target)
        if not (target / 'index.html').is_file():
            raise FileNotFoundError(f'{module} docJar has no index.html at its root')
    (OUT / '404.html').write_text(layout('Page not found', 'README', '<h1>Page not found</h1><p><a href="'+BASE+'">Return to documentation</a></p>', '404.html'))
    (OUT / 'style.css').write_text(CSS)
    (OUT / 'site.js').write_text(JS)
    (OUT / 'search.json').write_text(json.dumps(search))
    (OUT / '.nojekyll').touch()
    urls = ''.join(f'<url><loc>https://worxbend.github.io{BASE}{p["url"]}</loc></url>' for p in search)
    (OUT / 'sitemap.xml').write_text(f'<?xml version="1.0" encoding="UTF-8"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">{urls}</urlset>')
    check_links()
    print(f'Built {len(search)} guides plus 3 API references at {OUT}; local links passed for {BASE}')


if __name__ == '__main__':
    main(sys.argv[1:])
