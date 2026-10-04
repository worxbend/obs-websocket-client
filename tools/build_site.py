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
          ('contributing', 'Contributing'), ('releases', 'Releases')]


def target(link, page='README'):
    parsed = urlparse(link)
    if parsed.scheme or parsed.netloc:
        return link
    if not parsed.path:
        filename = ('index' if page == 'README' else page) + '.html'
        return parsed._replace(path=filename).geturl() if parsed.fragment else link
    if parsed.path.startswith('/'):
        return link
    path = posixpath.normpath(posixpath.join('docs', posixpath.dirname(page), parsed.path))
    pages = {f'docs/{name}.md': ('index' if name == 'README' else name) + '.html'
             for name, _ in GUIDES}
    if path in pages:
        path = pages[path]
    elif not path.startswith('assets/'):
        path = 'https://github.com/worxbend/obs-websocket-client/blob/main/' + path
    return parsed._replace(path=path).geturl()


def inline(text, page='README'):
    tokens = re.compile(r'`([^`]+)`|\[([^\]]+)\]\(((?:[^()]|\([^()]*\))+)\)|\*\*(.+?)\*\*')
    rendered, end = [], 0
    for match in tokens.finditer(text):
        rendered.append(escape(text[end:match.start()]))
        code, label, link, strong = match.groups()
        if code is not None:
            rendered.append('<code>' + escape(code) + '</code>')
        elif label is not None:
            rendered.append(f'<a href="{escape(target(link, page), quote=True)}">{inline(label, page)}</a>')
        else:
            rendered.append('<strong>' + inline(strong, page) + '</strong>')
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


def markdown(source, page='README'):
    # Support the constructs used by the offline guides; fail on malformed tables/fences.
    result, paragraph, code = [], [], None
    language = ''
    if source.startswith('---\n'):
        metadata, separator, source = source[4:].partition('\n---\n')
        title = re.search(r'^title: "(.+)"$', metadata, re.M)
        if not separator or not title:
            raise ValueError('Guide frontmatter needs a quoted title and closing separator')
        result.append('<h1>' + escape(title[1]) + '</h1>')
    in_list = False
    table = []
    def flush():
        if paragraph:
            result.append('<p>' + inline(' '.join(paragraph), page) + '</p>')
            paragraph.clear()
    for line in source.splitlines():
        if code is None and line.startswith('|'):
            flush()
            if in_list:
                result.append('</ul>')
                in_list = False
            table.append(line)
            continue
        if table:
            result.append(table_html(table, page))
            table.clear()
        if line.startswith('```'):
            flush()
            if code is None:
                code = []
                language = line[3:].strip()
            else:
                result.append(code_html(language, code))
                code = None
            continue
        if code is not None:
            code.append(line)
            continue
        if not line.startswith('- ') and in_list:
            result.append('</ul>')
            in_list = False
        if line.startswith('#'):
            level = len(line) - len(line.lstrip('#'))
            if line[level:level + 1] == ' ':
                flush()
                title = line[level:].strip()
                anchor = re.sub(r'[^a-z0-9]+', '-', title.lower()).strip('-')
                result.append(f'<h{min(level, 6)} id="{anchor}">{inline(title, page)}</h{min(level, 6)}>')
                continue
        if line.startswith('- '):
            flush()
            if not in_list:
                result.append('<ul>')
                in_list = True
            result.append('<li>' + inline(line[2:], page) + '</li>')
        elif not line:
            flush()
        else:
            paragraph.append(line)
    flush()
    if table:
        result.append(table_html(table, page))
    if in_list:
        result.append('</ul>')
    if code is not None:
        raise ValueError('Unclosed code fence')
    return '\n'.join(result)


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
    filename = filename or ('index' if name == 'README' else name) + '.html'
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


def check_links():
    errors = []
    # Check guide and generated API links and assets under the repository prefix.
    for path in OUT.rglob('*.html'):
        parser = Links()
        source = path.read_text()
        parser.feed(source)
        uses_site_base = f'<base href="{BASE}">' in source
        for link in parser.links:
            parsed = urlparse(link)
            if parsed.scheme or link.startswith('#'):
                continue
            relative = unquote(parsed.path)
            if relative.startswith('/'):
                if not relative.startswith(BASE):
                    errors.append(f'{path.name}: escapes repository base path: {link}')
                    continue
                dest = OUT / relative[len(BASE):]
            else:
                # Every generated guide uses <base href="/obs-websocket-client/">.
                # Scaladoc pages retain their own relative asset layout.
                dest = (OUT if uses_site_base else path.parent) / relative
            if not dest.exists():
                errors.append(f'{path.name}: missing {link}')
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
        filename = ('index' if name == 'README' else name) + '.html'
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
