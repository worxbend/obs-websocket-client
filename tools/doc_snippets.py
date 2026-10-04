#!/usr/bin/env python3
"""Compile documentation examples in typed session context; never execute them."""
from pathlib import Path
import re
import sys

def main(destination):
    root = Path(__file__).resolve().parents[1]
    out = Path(destination)
    out.mkdir(parents=True, exist_ok=True)
    source = ['package com.worxbend.obs.websocket.client.documentation', '', 'object DocumentationSnippets:']
    count = 0
    for page in [root / 'README.md', *sorted((root / 'docs').glob('*.md'))]:
        for snippet in re.findall(r'```scala\n(.*?)\n```', page.read_text(), flags=re.S):
            count += 1
            source.append(f'  // {page.name}, Scala block {count}')
            source.append(f'  def snippet{count}(session: com.worxbend.obs.websocket.client.ObsSession): Any =')
            source.append('    val _ = session')
            source.extend('    ' + line if line else '' for line in snippet.splitlines())
            # Some teaching examples intentionally name the outcome for subsequent use.
            if values := re.findall(r'^val ([A-Za-z_][A-Za-z_0-9]*)\s*=', snippet, flags=re.M):
                source.append('    ' + values[-1])
            elif definitions := re.findall(r'^def ([A-Za-z_][A-Za-z_0-9]*)\(', snippet, flags=re.M):
                source.append('    ' + definitions[-1])
            source.append('')
    if count == 0:
        raise SystemExit('No documentation examples found; compilation would be vacuous')
    (out / 'DocumentationSnippets.scala').write_text('\n'.join(source))
    print(f'Prepared {count} verbatim Scala documentation snippets for compilation')


if __name__ == '__main__':
    main(sys.argv[1])
