#!/usr/bin/env python3
"""Build and validate the offline documentation site using only Python's standard library."""
from html import escape
from html.parser import HTMLParser
from pathlib import Path
import json
import re
import shutil
import sys
from urllib.parse import unquote, urlparse
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'out/site'
BASE = '/obs-websocket-client/'
GUIDES = [('README', 'Home'), ('quickstart', 'Getting started'), ('requests', 'Requests'),
          ('events', 'Events'), ('recipes', 'Recipes'), ('compatibility', 'Compatibility'), ('server', 'HTTP sample'),
          ('contributing', 'Contributing'), ('releases', 'Releases')]


def target(link):
    if link.startswith('../'):
        return 'https://github.com/worxbend/obs-websocket-client/blob/main/' + link[3:]
    return link.replace('README.md', 'index.html').replace('.md', '.html')


def inline(text):
    text = escape(text)
    text = re.sub(r'`([^`]+)`', r'<code>\1</code>', text)
    return re.sub(r'\[([^\]]+)\]\(([^)]+)\)', lambda m: f'<a href="{escape(target(m[2]), quote=True)}">{m[1]}</a>', text)


def markdown(source):
    # The project's guides deliberately use headings, paragraphs, lists, and fenced code.
    result, paragraph, code = [], [], None
    in_list = False
    def flush():
        if paragraph:
            result.append('<p>' + inline(' '.join(paragraph)) + '</p>')
            paragraph.clear()
    for line in source.splitlines():
        if line.startswith('```'):
            flush()
            if code is None:
                code = []
            else:
                result.append('<pre><button class="copy" aria-label="Copy code">Copy</button><code>' + escape('\n'.join(code)) + '</code></pre>')
                code = None
            continue
        if code is not None:
            code.append(line)
            continue
        if not line.startswith('- ') and in_list:
            result.append('</ul>')
            in_list = False
        if line.startswith('#'):
            flush()
            level = len(line) - len(line.lstrip('#'))
            title = line[level:].strip()
            anchor = re.sub(r'[^a-z0-9]+', '-', title.lower()).strip('-')
            result.append(f'<h{level} id="{anchor}">{inline(title)}</h{level}>')
        elif line.startswith('- '):
            flush()
            if not in_list:
                result.append('<ul>')
                in_list = True
            result.append('<li>' + inline(line[2:]) + '</li>')
        elif not line:
            flush()
        else:
            paragraph.append(line)
    flush()
    if in_list:
        result.append('</ul>')
    if code is not None:
        raise ValueError('Unclosed code fence')
    return '\n'.join(result)


CSS = '''
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
        parser.feed(path.read_text())
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
                dest = path.parent / relative
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
        (OUT / filename).write_text(layout(title, name, markdown(source)))
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
