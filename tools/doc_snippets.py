#!/usr/bin/env python3
"""Compile documentation examples in typed session context; never execute them."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]


def snippet_destination(root, destination):
    """Resolve the complete generated filename before creating or writing anything."""
    output_root = root.resolve() / 'out'
    path = (Path(destination) / 'DocumentationSnippets.scala').resolve()
    if not path.is_relative_to(output_root):
        raise ValueError('Documentation snippets must be written beneath repository out/')
    return path


def main(destination):
    root = ROOT
    target = snippet_destination(root, destination)
    target.parent.mkdir(parents=True, exist_ok=True)
    source = ['package com.worxbend.obs.websocket.client.documentation', '', 'object DocumentationSnippets:']
    count = 0
    for page in [root / 'README.md', *sorted((root / 'docs').rglob('*.md'))]:
        for snippet in re.findall(r'```scala[^\S\n]*\n(.*?)\n```', page.read_text(), flags=re.S):
            count += 1
            source.extend([f'  // {page.name}, Scala block {count}',
                           f'  def snippet{count}(session: com.worxbend.obs.websocket.client.ObsSession): Any =',
                           '    val _ = session'])
            source.extend('    ' + line if line else '' for line in snippet.splitlines())
            # Some teaching examples intentionally name the outcome for subsequent use.
            if values := re.findall(r'^val (?a:([A-Za-z_]\w*))\s*=', snippet, flags=re.M):
                source.append('    ' + values[-1])
            elif definitions := re.findall(r'^def (?a:([A-Za-z_]\w*))\(', snippet, flags=re.M):
                source.append('    ' + definitions[-1])
            source.append('')
    if count == 0:
        raise SystemExit('No documentation examples found; compilation would be vacuous')
    target.write_text('\n'.join(source))
    print(f'Prepared {count} verbatim Scala documentation snippets for compilation')


if __name__ == '__main__':
    main(sys.argv[1])
