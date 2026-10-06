# Code generation

The `codegen` Mill module is a Python/Jinja offline schema compiler. It is the only generator implementation. Published Scala artifacts have no Python runtime dependency.

## Toolchain and build invocation

Provision Python **3.12.12**, recorded in `.python-version`. Set `OBS_CODEGEN_PYTHON` to its command or absolute executable path when it is not the default `python3`. Mill checks the exact version before generation and creates isolated virtual environments under `out/`. It installs checked-in hash-locked requirements, never into the host interpreter.

```sh
export OBS_CODEGEN_PYTHON=/absolute/path/to/python3.12
./mill --no-server protocol.generatedSources
./mill --no-server show protocol.generatedSources
./mill --no-server protocol.compile
```

The export is an example: replace the path with your installed Python 3.12.12 executable. On PowerShell set `$env:OBS_CODEGEN_PYTHON` and use `.\mill.bat`. The build uses platform-specific virtualenv executable paths; Linux is locally verified, while macOS/Windows remain covered by the compatibility workflow rather than a claim of local execution.

`protocol.generatedSources` invokes `codegen/src/main.py` through native Mill `PythonModule.runner`. It passes four positional arguments:

1. `protocol-spec/protocol.json`: pinned upstream schema bytes.
2. `out/protocol/generatedSources.dest/scala/`: task-owned generated source root.
3. `protocol-spec/overrides.json`: reviewed semantic corrections.
4. `protocol-spec/provenance.json`: schema checksum and upstream documentation origin.

The inputs are absolute paths resolved from the checkout root. Python sources, templates, requirements, interpreter selection, schema, overrides, and provenance participate in the task dependency graph. The shared runner explicitly depends on the complete virtual environment: Mill 1.1.10's native interpreter-only dependency can otherwise miss installed-package changes. Environment creation also directly tracks the interpreter executable fingerprint, so replacement at an unchanged path invalidates the environment.

The task returns the generated Scala subtree alongside inherited source roots. Python bytecode caches stay outside that subtree. Compiling `protocol` or any dependent module runs generation first when inputs change; failed generation blocks compilation.

Ordinary generation makes no upstream requests. Initial interpreter/dependency provisioning may need network access; fully provisioned generation is offline. Runtime and developer dependency locks are separate. Updating dependencies requires reviewed exact versions and distribution hashes, plus tests of the resulting environment; normal builds never regenerate locks.

## Output and ownership

The pinned catalog produces 218 files: 147 request/response files, 60 event files, seven enum files, and four shared files. Outputs are `requests/<Name>.scala`, `events/<Name>.scala`, `enums/<Name>.scala`, `Event.scala`, `Catalog.scala`, `RequestApi.scala`, and `catalog-inventory.tsv`. Scala package declarations establish namespaces; no extra package-directory prefix is added.

Mill cleans the generated task destination on rerun. Do not edit or commit generated sources. Standalone CLI callers must use a fresh output directory: the writer validates all paths before writing, rejects duplicate/case-folded or resolved aliases and escaping symlinks, but does not delete stale caller-owned files. Rendering finishes before writing. Multi-file writes are not transactional; filesystem failures fail the build rather than returning a successful partial source root.

## Responsibilities

Implementation lives in `codegen/src/obs_codegen/`:

- `cli.py` preserves the four-argument interface and coordinates the pipeline.
- `inputs.py` reads bytes and checks SHA-256 against provenance.
- `parsing.py` validates raw JSON shapes without coercing booleans into numbers.
- `model.py` holds immutable records.
- `normalize.py` resolves supported types, wire/member names, nullable overrides, categories, enum expressions, and deterministic order.
- `scala.py` handles Scala literal and documentation escaping.
- `render.py` selects templates and returns complete relative-path/content records.
- `output.py` validates destinations and writes UTF-8 files.
- `errors.py` provides contextual generator errors.

The parser/normalizer do not depend on templates. Templates do not read files or decode JSON. The generated Scala API is independent of generator implementation types.

## Editing templates

`codegen/templates/` contains complete-file Jinja templates for request/response, event, enum, event dispatch, catalog, request API, and inventory output. Shared macros handle repeated source/field syntax; keep full declarations readable in their output-family template rather than assembling opaque whole-class fragments in Python.

Jinja runs with StrictUndefined, HTML autoescaping disabled, explicit LF output, and preserved trailing newlines. Keep whitespace intentional. Do not trim complete rendered files or change golden expectations merely to silence a formatting difference. Semantic decisions belong in normalization; layout belongs in templates.

Keep Scala literal escaping separate from Scaladoc escaping. Wire names survive member renaming. Optional, nullable, and dotted fields retain distinct codec behavior. Dispatch maps remain chunked into 24 entries to avoid JVM method-size limits. Unknown future enum values and established raw-object behavior are preserved.

## Tests and quality gates

```sh
./mill --no-server codegen.test
./mill --no-server 'codegen.test.{lint,formatCheck,typeCheck}'
./mill --no-server codegen.test.coverage
./mill --no-server protocol.test
python3 tools/check_generation.py
python3 -m unittest discover -s tools -p 'test_*.py'
tools/coverage.sh
```

Run commands separately or use Mill brace selectors. Arguments after a runnable command can become test/application arguments. `codegen.test.testCached` is available for local no-change checks; the coverage command deliberately produces fresh evidence every time.

The Python suite retains the nine miniature golden files in `codegen/test/resources/golden/expected` and compares the full catalog against `codegen/test/resources/catalog-sha256.json`, captured from the pre-migration Scala generator. The hash fixture establishes historical byte parity, not just repeatability. The existing Scala protocol tests independently exercise generated APIs and codecs.

`check_generation.py` verifies provenance, compares two independent generations, and compares them with the Mill-managed source subtree. Python tests cover parsing, normalization, field combinations, documentation/escaping, dispatch chunking, CLI behavior, and filesystem failures. Ruff and strict mypy use explicit production/test roots and the tracked `pyproject.toml` configuration.

`tools/coverage.sh` enforces exact complete statements and branches for nine Scala modules plus the Python generator. Python evidence includes tests, source inventory, static statement/branch inventory, and fresh coverage reports. Missing, stale, skipped, excluded, or incomplete evidence fails. Python percentages do not measure Jinja branch coverage; render matrices, goldens, and generated Scala tests cover template behavior.

For intentional schema/output changes, review the full generated diff and public API before updating hashes or fixtures. Keep provenance and overrides synchronized with the reviewed schema. Never fetch a moving upstream branch during generation.
