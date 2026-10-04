# Architecture diagram assets

The source of truth is the Mermaid fences in [docs/architecture.md](../../docs/architecture.md). GitHub renders those fences directly. The offline site replaces each fence with its checked-in SVG, identified by the first-line `%% asset: filename.svg` comment. No browser-side Mermaid runtime or network fetch is required.

SVGs were rendered with `@mermaid-js/mermaid-cli` 11.12.0 using the neutral theme and a 1400-pixel viewport. Each SVG records the SHA-256 of its exact fence body, without a final newline, in `data-source-sha256`. The site build rejects missing or stale assets.

## Update the diagrams

Edit the Mermaid in the architecture page. Install the renderer in a temporary directory; it is an authoring tool, not a project runtime dependency:

```sh
npm install --prefix /tmp/obs-doc-diagrams --no-audit --no-fund @mermaid-js/mermaid-cli@11.12.0
```

The renderer needs a compatible Chromium installation. On machines where Puppeteer browser downloads are disabled, set `PUPPETEER_EXECUTABLE_PATH` to an installed compatible browser. Run the following from the repository root to extract, render, and bind each asset to its source:

```python
from hashlib import sha256
from pathlib import Path
import re
import subprocess
import tempfile

renderer = Path("/tmp/obs-doc-diagrams/node_modules/.bin/mmdc")
page = Path("docs/architecture.md").read_text()
for source in re.findall(r"```mermaid\n(.*?)\n```", page, flags=re.S):
    name = re.match(r"%% asset: ([a-z0-9-]+\.svg)\n", source)[1]
    output = Path("assets/architecture") / name
    with tempfile.TemporaryDirectory() as directory:
        input_file = Path(directory) / "diagram.mmd"
        input_file.write_text(source)
        subprocess.run(
            [str(renderer), "-i", str(input_file), "-o", str(output),
             "-t", "neutral", "-w", "1400"],
            check=True,
        )
    digest = sha256(source.encode()).hexdigest()
    output.write_text(output.read_text().replace(
        "<svg ", f'<svg data-source-sha256="{digest}" ', 1
    ))
```

Review the rendered diagrams, then run the existing checks:

```sh
python3 -m unittest discover -s tools -p 'test_*.py'
./mill --no-server site.build
```

The site also supports empty `scala file=examples/src/...scala` fences to embed an existing compiled companion directly. The companion is compiled by `examples.compile`/`examples.test`; ordinary Scala snippets under `docs/` are compiled by `integration.test.compile`. Source links beside embeds keep the companion accessible when reading Markdown directly on GitHub.
