# Python/Jinja codegen migration: research, design, and implementation plan

Status: production migration implemented. The research and task checklist below are retained as historical design notes, not the current implementation status.

## Implemented cutover

- `codegen` is now a native Mill Python module. `protocol.generatedSources` runs Python/Jinja and compiles its output into the Scala protocol module.
- The Scala generator and its Scala tests have been removed. Scala files under the golden fixture directory are expected output, not a second implementation.
- Python 3.12.12 is pinned in `.python-version`. Set `OBS_CODEGEN_PYTHON` to that interpreter when the host `python3` differs; runtime and developer requirements use exact versions and hashes.
- The new generator preserves all 218 baseline paths and bytes, including the unchanged miniature fixtures. A durable full-catalog SHA-256 fixture guards parity.
- The production Python suite contains 41 tests; Ruff, strict mypy, generation checks, downstream Scala tests, documentation snippets/site, package reports, and runtime dependency checks have passed locally.
- CI uses the new generator on Linux/macOS/Windows; only Linux has been executed locally. Platform path support is implemented, not evidence of a remote CI pass.
- Exact Python coverage replaces retired Scala-generator Scoverage; the combined gate still measures nine Scala modules plus codegen. The combined gate passed with 11,958/11,958 statements and 917/917 branches. No production coverage exemption was introduced.
- Final independent review identified a same-path interpreter replacement cache risk. The venv task now directly tracks the executable fingerprint; an isolated real executable replacement rebuilt the environment, while unchanged runs stayed cached.
- Consumer smoke was rerun and still fails on the pre-existing private `ObsConfig` companion. This is not a generator regression; it remains a separate release blocker.
- Current commands, package boundaries, output ownership, and troubleshooting are in [docs/code-generation.md](docs/code-generation.md).

The old baseline and isolated proof-of-concept described below explain the migration. They are not used by the repository build. Performance comparisons and broader runtime-review findings remain separate work; the migration does not claim a measured speedup or fix unrelated pre-existing runtime defects.

Reviewed baseline: `f360e42ff8ead3b04b4a08eee5df30617c85433a` on `main`.
Toolchain: Mill 1.1.10, Scala 3.9.0, Java 25; client concurrency uses Ox 1.0.9.

## Read this first

**Recommendation: migrate the build-time generator to Python and Jinja, preserving the generated Scala contract.** The strongest reason is maintainability: complete-file templates make emitted Scala easier to inspect. Faster generation is plausible, but not established by the measurements below.

Keep the existing good boundaries: input validation, normalized immutable model, rendering, and filesystem ownership. Replace source-string assembly, not the library's public API or concurrency model.

Three gates come first:

1. Freeze the existing full-catalog output and semantic fixtures before porting.
2. Carry the verified Mill environment-dependency fix into generation and cached Python tests.
3. Resolve native Windows support before switching the production build. Mill 1.1.10's native Python module hardcodes Unix virtualenv paths.

Do not delete Scala codegen until Python reproduces the full output, generated Scala compiles, and downstream quality gates pass.

### What this work delivered

- Parallel architecture review and Python migration research.
- An executed, isolated native Mill/Python/Jinja integration spike.
- Verification of source/template/schema/dependency invalidation and negative cases.
- A measured Scala-generator baseline, without an unsupported Python speedup claim.
- Installed focused skills: `python-jinja-codegen` and `mill-python-codegen-integration`.
- A staged plan below, including tests, CI, coverage, portability, rollback, and acceptance criteria.

No production generator, build definition, runtime API, or existing test was changed for this research. Python/Jinja dependencies were installed only in the isolated spike environments, not into the host interpreter. This document is the repository deliverable. Checklist items below remain implementation work.

## 1. Current generator: retain the design that already works

Source prefix below: `codegen/src/com/worxbend/obs/websocket/client/codegen/`.

The existing implementation is not an unstructured monolith:

- `Generate.scala` owns CLI arguments, input reads, checksum verification, and output writes.
- `schema/SchemaParser.scala` decodes inputs.
- `schema/SchemaNormalizer.scala` validates semantics and prepares Scala-facing definitions.
- `model/` contains immutable normalized records.
- `CodeGenerator.scala` chooses filenames and constructs typed template contexts.
- `templates/` renders each output family; `FieldFragments.scala` and `SourceFragments.scala` share syntax.
- `ScalaLiteral.scala` keeps Scala literal escaping separate from documentation escaping.

The dependency direction is sound. The main maintenance cost is reconstructing complete Scala declarations from interpolated fragments, indentation rules, and repeated branches.

### Build and output contract

`build.mill:75–132` defines Scala codegen and invokes it from `protocol.generatedSources`. It currently compiles the generator, obtains its runtime classpath, then starts a separate Java process.

The CLI accepts four positional arguments, in this order:

1. `protocol-spec/protocol.json`
2. Output directory
3. `protocol-spec/overrides.json`
4. `protocol-spec/provenance.json`

Retain this interface initially. Existing verification tools call it directly.

The pinned catalog currently produces 218 files:

- 147 request/response files under `requests/`.
- 60 event files under `events/`.
- 7 enum files under `enums/`.
- `Event.scala`, `Catalog.scala`, `RequestApi.scala`, and `catalog-inventory.tsv`.

These are seven output families, not seven generated files. Scala package declarations establish namespaces; there is no extra `com/worxbend/...` directory prefix.

The generator verifies the checksum of the original schema bytes. It does not fetch a schema, embed a current timestamp, or introduce a runtime dependency into published client artifacts.

`Generate.scala:63–68` currently writes rendered files nontransactionally. Mill cleans its task-owned destination on rerun. Standalone callers must use fresh output directories to avoid stale files. Preserve or explicitly improve this contract; do not silently add recursive deletion of arbitrary caller directories.

### Existing evidence

Executed against the reviewed baseline:

- `./mill --no-server -j 1 '{codegen,protocol,core,sttp,okhttp,zio,fs2,pekko,examples,server}.test'`: 2,969 tests across 45 suites; zero failed or ignored.
- `python3 -m unittest discover -s tools -p 'test_*.py'`: 33 tests passed.
- `python3 tools/check_generation.py`: `Pinned schema verified; 218 generated files match byte-for-byte`.
- `tools/consumer_smoke.sh`: failed. External consumer compilation reports `Expected a term, but found a type: ObsConfig`.

The last failure is pre-existing: `core/src/com/worxbend/obs/websocket/client/ObsConfig.scala:125` makes the case-class companion `private[client]`. In-package tests do not expose the external construction problem. Fix it independently; do not weaken or skip consumer smoke during migration.

These results are not a fresh full-coverage result. Coverage, all release gates, and cross-platform execution were not established by this review.

## 2. Target architecture

Use a small synchronous schema compiler. No web framework, async pipeline, DI container, generic plugin platform, or class hierarchy for every transformation.

### Proposed package layout

The following paths are proposed, not existing implementation:

```text
codegen/
  pyproject.toml
  requirements.in
  requirements.lock
  requirements-dev.in
  requirements-dev.lock
  src/
    main.py
    obs_codegen/
      __init__.py
      cli.py
      inputs.py
      errors.py
      schema.py
      parsing.py
      model.py
      normalize.py
      scala.py
      render.py
      output.py
  templates/
    request.scala.j2
    event.scala.j2
    enum.scala.j2
    event_dispatch.scala.j2
    catalog.scala.j2
    request_api.scala.j2
    inventory.tsv.j2
    macros/
      documentation.j2
      fields.j2
  test/
    src/
      conftest.py
      test_parsing.py
      test_normalize.py
      test_scala.py
      test_render.py
      test_output.py
      test_cli.py
    resources/
      golden/                  # Preserve existing reviewed fixtures.
      invalid/                 # Add narrowly scoped negative cases.
```

Use this as a responsibility map, not a quota for files. Split `normalize.py` by cohesive concern only if the real port warrants it. Do not copy Scala's one-class-per-file convention into Python.

During parallel development, use a temporary `codegenPython` Mill object whose module directory explicitly points to `codegen`. Keep the existing Scala `codegen` object active until parity. The directory override is proposed integration work and must be compiled against Mill 1.1.10. Remove the temporary object at cutover; do not maintain two permanent production generators.

### Boundaries and contracts

1. **Inputs:** read schema, overrides, and provenance bytes; retain source paths; verify SHA-256 before output.
2. **Parsing:** convert untrusted JSON values into explicit raw records. Validate types, required properties, default behavior, and supported representations.
3. **Normalization:** resolve names, types, nullable overrides, categories, ordering, and enum semantics. No Jinja or filesystem dependency.
4. **Rendering:** accept validated immutable definitions and explicit provenance; select repository-owned templates; return complete relative-path/content pairs.
5. **Output:** validate every destination, then write only into the owned output subtree.
6. **CLI:** parse arguments and translate expected generator/input failures into concise stderr diagnostics and a nonzero exit. Do not swallow programming errors as success.

Suggested records are `Schema`, `NormalizedSchema`, `RequestDefinition`, `EventDefinition`, `EnumDefinition`, `Field`, and `RenderedFile`. These names describe proposed Python types, not APIs already implemented.

Prefer `@dataclass(frozen=True, slots=True)` with tuples for immutable children. A frozen dataclass containing a mutable dictionary is not deeply immutable. Use small enums/unions for real alternatives. Avoid propagating `dict[str, Any]` from JSON through the entire pipeline.

Functions are sufficient for pure parsing and normalization. A small renderer object is justified if it owns the configured Jinja environment. An output writer need not have an interface unless multiple implementations are actually needed. This applies separation of concerns and composition without ceremonial OOP.

Keep a narrow typed boundary around Jinja's dynamic context. `StrictUndefined` catches missing values at render time, not statically. Python type checking cannot replace rendering every template branch and compiling generated Scala.

## 3. Compatibility contract: port behavior before improving it

The first production Python generation must match the current pinned catalog's path/byte map exactly. Formatting changes, schema upgrades, stronger generated types, and API redesign belong in separate changes.

### Data and wire behavior

- Preserve request names, response names, package names, constructor order, defaults, facade categories, and public method names.
- Keep wire keys distinct from Scala member identifiers. Renamed fields must serialize using their original keys.
- Preserve optional versus nullable behavior. Optional fields use `Field[A]` and default to `Field.Missing`; required nullable fields use `Option[A]`.
- Preserve runtime rejection of explicit null for nonnullable optional fields. Introducing a separate omission-only public type would change generated signatures.
- Preserve dotted-field decoding/encoding through nested objects, including missing or malformed parents.
- Preserve empty-payload acceptance and unknown future field handling.
- Preserve enum wrappers that retain unknown values rather than closing the universe with an exhaustive enum.
- Keep raw nested objects and opt-in validated projections. Do not replace raw objects with guessed schemas.
- Keep `RequestApi[E]` and its direct-style `Either` contract. Python codegen does not justify an effect-system migration.

### Exact JSON and Python semantics

Type annotations are not validation. Decode to a restricted JSON value model and check each field explicitly.

- Python `bool` is a subclass of `int`. Use exact type checks where booleans are forbidden as numbers.
- Keep missing, null, false, zero, empty string, and empty collection distinct. Avoid `value or default`.
- Parse decimals without binary floating-point loss. Use `Decimal` where exact decimal input is needed, and explicit bounds for generated Scala `Int`/`Long` values.
- Reject nonstandard numeric constants such as NaN and Infinity unless the existing contract explicitly allows them.
- Characterize current duplicate-key and unknown-key behavior before choosing a policy. Python's default JSON behavior is not automatically parity.
- Missing/null collections and optional metadata may have parser-specific defaults. Preserve tested behavior instead of imposing blanket strictness.
- Preserve array ordering where meaningful. Sort only the collections the current normalizer sorts.

The existing generator sorts requests, events, and enums by upstream name. It preserves field and enum-constant declaration order. Inventory sorting is based on rendered rows. Keep this deterministic contract.

### Scala lexical behavior

`ScalaLiteral.scala`, `SchemaNormalizer.scala`, and `SourceFragments.scala` are the reference, not Python convenience functions.

- Implement and test Scala string quoting separately. Python `repr`, Jinja `tojson`, JSON escaping, and HTML escaping are not substitutes.
- Preserve quote/backslash escaping and control-character spelling, including lowercase `\\uXXXX` forms where the baseline emits them.
- Preserve keyword handling, backticks, payload-field renaming, and normalized-name collision detection.
- Request methods lowercase the first character; category capitalization has its own rules. Do not use Python `.capitalize()` indiscriminately: it also lowercases the remainder.
- Match documentation whitespace normalization deliberately. Java/Scala trimming and regex whitespace classes need not match Python's Unicode defaults.
- Escape `*/` in Scaladoc and preserve parameter links, deprecation text, version metadata, and pinned upstream URLs.
- Preserve chunked dispatch generation: the existing decoder maps use chunks of 24 entries to avoid JVM method-size limits.

### Separate hardening backlog

The review identified future/custom-schema risks. They are not evidence that the pinned catalog currently fails compilation.

- Validate top-level generated names and output-path containment.
- Detect collisions with inherited/generated members such as `validate` and `getClass`.
- Detect collisions after facade-method transformation, including `Foo` versus `foo`.
- Check enum shifts use appropriate numeric width. A Long wrapper does not make an embedded Int shift such as `(1 << 32)` correct.
- Detect unsupported forward, self, or cyclic enum references rather than emitting initialization hazards.

Path containment and no-code-execution safeguards belong in the new writer/parser immediately. Other semantic hardening needs separate negative fixtures and reviewed expectations. Do not “preserve parity” by keeping unsafe writes, and do not silently change valid pinned output while hardening hypothetical schemas.

Never use Python `eval` or `exec` for enum expressions. Recognize a restricted grammar, validate references, and render or evaluate only supported operations.

## 4. Jinja conventions

### Complete files stay readable

Each main template should visibly contain its generated case class, companion, methods, or dispatch structure. Python prepares semantic values, not a giant finished Scala declaration hidden behind `{{ body }}`.

Use small macros for genuinely repeated syntax: documentation, parameters, field reads/writes. Do not recreate the current fragment-builder problem as dozens of string-returning Python helpers.

Template decisions should concern layout and already-classified fields. Schema interpretation, rename rules, enum dependency analysis, and collision detection belong in Python normalization.

### Environment

Configure explicitly:

- `undefined=StrictUndefined`
- `autoescape=False`
- `keep_trailing_newline=True`
- `newline_sequence="\n"`
- Explicit, golden-tested `trim_blocks` and `lstrip_blocks` settings.

Render UTF-8 with LF regardless of host platform. Never run `.strip()` over a completed source file. Review whitespace changes against goldens instead of using aggressive whitespace controls until the diff disappears.

Use an absolute `FileSystemLoader` root supplied by the build for repository-local templates. This matches the tracked template-directory spike and avoids package-resource complexity. If the generator is later packaged for installation, include templates as package data and test `PackageLoader` outside the checkout.

Only trusted repository templates are executable template input. Schema strings remain data and must not become template names or template source. Loader symlink behavior is not a security sandbox. Check template/output ownership separately.

Pass macro arguments explicitly. Jinja imports do not inherit the current context by default. Avoid globals whose missing values happen to work in one output family but fail in another.

## 5. Native Mill integration: verified behavior and limitations

The spike used native `mill.pythonlib.PythonModule` from **Mill 1.1.10**, not a handwritten shell-only Python module or an extra plugin.

Executed configuration:

- Python 3.12.12, externally provisioned.
- Jinja2 3.1.6 and MarkupSafe 3.0.3 with pinned distribution hashes.
- Generated Scala 3.9.0 compiled and executed under Java 25.
- Two stdlib unittest tests passed.

These are tested versions, not a claim that they are the newest or a completed dependency-security review. Pin and review the selected production versions during implementation.

### Verified task edge

The following API shape was compiled and exercised in the isolated spike. It is not a drop-in replacement for this repository's entire `build.mill`:

```scala
import mill.*
import mill.pythonlib.{PythonModule, TestModule as PythonTestModule}

trait PinnedPython extends PythonModule {
  // The executed spike removes native mypy/pex defaults so the hashed
  // runtime lock is not combined with unhashed tool requirements.
  def pythonToolDeps = Seq.empty[String]
  // Provision and verify hostPythonCommand separately.
  override def runner = Task.Anon {
    val _ = venv()
    super.runner()
  }
}

object codegen extends PinnedPython {
  def pythonRequirementFiles = Task.Sources("requirements.lock")
  def templates = Task.Source("templates")

  object test extends PythonTests with PinnedPython with PythonTestModule.Unittest {
    def resources = Task { super.resources() ++ Seq(codegen.templates()) }
  }
}
```

The Scala consumer calls `codegen.runner().run(...)` inside a cached `generatedSources` task and returns `super.generatedSources() ++ Seq(PathRef(output))`.

Use `Task.dest / "scala"` for generated source output. The runner also creates bytecode-cache files beneath the task destination; these must not contaminate the source inventory.

Retain `protocol.specification`, `protocol.overrides`, and `protocol.provenance` as tracked inputs. Pass their absolute paths and the four existing positional CLI arguments. The spike used its own simpler flagged CLI; it did not port the real OBS CLI.

The runner already tracks transitive Python sources and supplies `PYTHONPATH`. Track templates, fixtures, lockfiles, configuration, and schema-related inputs explicitly. Call the runner from the Scala task; do not try to use Scala `moduleDeps` for a Python module.

### Proven dependency-invalidation defect

Unmodified Mill 1.1.10's runner depends on `pythonExe`, whose `PathRef` covers the interpreter executable. Changing a locked package rebuilt the venv, but the executable value stayed unchanged and cached generation did not rerun.

Executed reproduction: installed MarkupSafe changed from 3.0.3 to 3.0.2; generated output still reported 3.0.3.

The shared override above explicitly depends on `venv()`. With that guard, a real dependency-version change reran generation, Scala compilation, and cached Python tests. This is required build correctness, not speculative tuning.

Adding the environment edge only to `protocol.generatedSources` would leave cached test consumers exposed. Put it in the shared Python trait and use that trait in nested test/tool modules too.

### Interpreter provisioning

The native API is `hostPythonCommand`, not an assumed `pythonVersion` downloader. Mill 1.1.10 invokes an existing interpreter with `-m venv`, then installs requirements using pip.

The spike tracked `.python-version` and a version-specific interpreter path, asserted exact `--version` output, and failed correctly on a mismatched version. Production needs an explicit bootstrap strategy in local documentation and CI. Do not commit the spike's Linux-specific interpreter symlink.

`uv` was used only for isolated provisioning and lock generation. It is optional external tooling, not native Mill functionality. If selected, pin uv as well. Ordinary builds should consume checked-in locks, never regenerate them or resolve latest versions.

Offline schema generation and offline environment provisioning are different claims. After interpreter/dependencies are provisioned, generation must work without network access. A clean machine still needs provisioned or cached artifacts.

### Dependency locks and tools

Use exact transitive versions and hashes. The spike verified that corrupting the Jinja distribution hashes fails installation.

Native PythonModule adds `mypy==1.13.0` and `pex==2.24.1` as tools. The spike removed these unused defaults; consequently it did not verify native `typeCheck` or `bundle`.

Production should explicitly control runtime and developer dependencies. Recommended tool roles:

- Jinja2 plus its locked transitive dependency for generation.
- pytest for generator tests.
- coverage.py with branch measurement; pytest-cov only if useful for integration.
- Ruff for lint and formatting.
- mypy in strict mode as the single type checker.

All versions and transitive hashes must be locked before their commands become gates. Do not combine unhashed native defaults with a hash-enforced lock accidentally. Mill 1.1.10's native `TestModule.Pytest` adds `pytest==8.3.3`; override or incorporate that declaration deliberately.

Keep runtime generation free of unnecessary developer tools. A separately configured test/tool module may consume the runtime module and the development lock, but its own interpreter and runner overrides must be explicit. Verify combined lock installation rather than assuming nested modules inherit every outer setting.

Source and configuration routing also require explicit wiring. Native Mill 1.1.10 `typeCheck` and Ruff tasks check their own `sources()`, not all dependency source roots. Native mypy runs from the task destination without `--config-file`; tracking `codegen/pyproject.toml` alone does not make mypy load it. Pass the intended production/test source roots and tracked absolute configuration paths explicitly. Ruff's native configuration input defaults to `ruff.toml`; override it deliberately if using `pyproject.toml`. Prove the gates catch an error in an unimported production file and respond to a configuration-only rule change. These production commands remain planned, not verified by the unittest spike.

### Tests and reports

`codegen.test` resolves to native `testForked`; it runs every invocation. `codegen.test.testCached` skips unchanged work. Both were exercised with unittest in the spike.

The native test-result type in 1.1.10 is a `Unit` placeholder. Do not expect JVM-style structured reports automatically. Configure pytest JUnit XML and coverage artifacts explicitly during the production port.

### Windows is a cutover blocker

The repository has Linux/macOS/Windows compatibility jobs. Mill 1.1.10's Python venv implementation uses `bin/python3`; native Windows uses a different layout. Only Linux was tested in the spike.

Before replacing Scala codegen:

1. Exercise a minimal native Windows build and retain its exact failure or success evidence.
2. Prefer a narrow platform-aware override in the shared Python build trait if the pinned Mill release requires it.
3. If a Mill upgrade is the better solution, make and validate that upgrade separately across the entire build.
4. Run generation, hash enforcement, invalidation, and Python tests on all supported platforms.

Do not drop Windows CI, switch it silently to WSL, or claim that the Linux spike proves cross-platform support.

### Spike acceptance already exercised

- Imported Python helper edit invalidates generation and Scala compilation.
- Template edit invalidates generation and cached tests.
- Schema edit changes the executed generated value.
- Removed output does not survive a Mill-managed regeneration.
- Malformed schema fails generation and blocks downstream compilation.
- Interpreter mismatch and corrupt requirement hashes fail.
- Unrelated compiled module stays unchanged.
- Repeated unchanged generation keeps generated/compiled artifacts unchanged.
- Real package-version changes invalidate consumers with the runner guard.

The initial parent rerun passed the generated Scala application and the original two Python tests. This established the integration mechanism, not a full OBS Python generator.

### Fresh-context follow-up verification

Two isolated reviewers subsequently checked the spike and this plan. The execution reviewer was interrupted before its final report; the parent inspected the changes and independently reran all added suites successfully.

Changes confined to the scratch spike:

- Added explicit rejection of non-object JSON schemas. Previously null, arrays, strings, numbers, and booleans raised incidental `AttributeError` exceptions from `.get`; they now raise a contextual `ValueError` before output is touched.
- Expanded the Python suite from 2 to 12 test methods. Coverage includes malformed/missing schema, invalid message types, real-renderer StrictUndefined behavior, preservation of old output on validation/render failure, deterministic bytes, empty messages, foreign-working-directory CLI execution, and propagated filesystem failures.
- Replaced the isolated Jinja configuration check with a test of the actual renderer. Removing StrictUndefined was verified to make the suite fail.
- Added a neutral-package Scala consumer that compares generated strings as UTF-16 code units, plus compiler/runtime round trips for empty strings, syntax escapes, Unicode/surrogates, and control characters.
- Added repeatable cache, dependency-change, and failure-injection harnesses. Each restores mutated inputs; tests run serially within the spike.

Parent-verified results:

- 12 Python tests passed.
- Four generated Scala literal round trips compiled and executed successfully.
- Imported-source/template/schema changes, stale-output cleanup, cached no-op behavior, and unrelated-module stability passed.
- MarkupSafe 3.0.3/3.0.2 dependency transitions invalidated generation and cached tests with the existing runner guard; the original lock was restored.
- Malformed/non-object schemas, invalid message types, undefined templates, invalid generated Scala, runtime assertion failures, wrong interpreter pin, corrupt hashes, and zero discovered tests all failed as expected.
- Fresh repository baseline: 2,485 Scala codegen/protocol tests across 8 suites passed; all 33 tooling tests passed.

The spike still uses JSON quoting for its single string-literal demonstration. The tested literals work, but this does not establish byte parity with the production Scala escaping contract. Full OBS generation, production Python lint/type/coverage gates, and Windows/macOS behavior remain unimplemented or unverified.

This review also corrected the example's missing `pythonToolDeps` override and added explicit source/configuration routing and fault-test requirements for developer-tool modules. Evidence lives in the spike's `fresh-evidence/` directory; `FRESH_REVIEW.md` records commands and scope. These scratch tests are not yet repository CI tests.

## 6. Output safety and determinism

Render the complete output map before opening destination files. Reject duplicate destinations before a dictionary could overwrite one silently.

Validate names and paths independently:

- Allow only the expected relative directory/file structure.
- Reject absolute paths, traversal, drive-qualified paths, and separator tricks on supported platforms.
- Resolve destinations under the declared owned root; account for existing symlinks.
- Detect case-insensitive output collisions to avoid platform-specific behavior.
- Never remove an arbitrary caller-supplied directory tree.

For the first migration, retain fresh-directory semantics for standalone runs and let Mill own stale cleanup. Use a staging subtree for writes if needed, but document its guarantee accurately: rendering-before-writing does not make a multi-file write transactional. A write failure must fail the task; compilation must never receive a successful partial result.

Use stable ordering, UTF-8, LF, and explicit final newlines. Output must not include timestamps, absolute checkout paths, random IDs, or interpreter/platform-dependent representations.

If output is nested beneath `generatedSources.dest/scala`, update tools that currently snapshot the whole destination. Comparing the whole task directory would incorrectly include Python caches.

## 7. Testing and quality gates

### Layered test strategy

1. **Parser characterization:** valid input, wrong JSON types, required/missing/null fields, booleans versus integers, exact numbers, duplicate properties, and contextual errors.
2. **Normalization:** field type/codec choices, nullability overrides, names/collisions, categories, ordering, dotted fields, enum constants, and invalid references.
3. **Scala syntax helpers:** quotes, slashes, controls, Unicode, identifiers, comment termination, whitespace, and numeric widths.
4. **Template matrices:** empty/nonempty forms and each optional/nullable/dotted combination; missing context must fail under StrictUndefined.
5. **Golden parity:** compare every filename and byte for existing miniature fixtures. Do not refresh expected files to conceal differences.
6. **Full-catalog parity:** compare Python output to the frozen Scala baseline, not merely Python run A to Python run B.
7. **Compiler and runtime checks:** compile generated Scala and run existing protocol/catalog/facade/wire tests plus external consumer smoke.
8. **Build invalidation:** Python helpers, templates, schema, overrides, provenance, requirements, interpreter declarations, and relevant tool configuration.
9. **Filesystem failures:** unsafe paths, duplicates, stale files, undefined templates, and failures before/during writes.

Keep Scala protocol fixtures independent of Python generation logic. A generator and its test oracle sharing the same mistake is not meaningful coverage.

Use function-scoped pytest fixtures and `tmp_path`. Avoid mutating dictionaries reused across parameterized tests. Register cleanup immediately after acquisition. Run the CLI from another working directory. Test failed invocations produce no successful source-root result.

### Coverage must not regress silently

The repository's existing coverage gate requires exact complete statement and branch coverage. Replacing Scala codegen removes it from Scoverage, but does not remove its quality obligation.

Required changes:

- Remove Python codegen from `coverage.measured` and Scala Scoverage module lists only when its Python replacement gate exists.
- Measure Python statements and branches with production source roots that include unimported modules.
- Require exact zero missing statements/branches; a rounded combined percentage is insufficient.
- Check nonempty source inventory, freshness, source/lock/template digests, and expected reports. Fail on missing or stale evidence.
- Add fault tests proving unimported modules, missing reports, changed templates/locks, and incomplete coverage cannot pass.
- Keep Jinja semantic coverage separate. Python coverage does not show which template branches rendered; use branch matrices, golden files, and generated Scala compilation.

`tools/check_coverage.py:50–60` currently hashes selected suffixes but omits `.j2`, `.toml`, and lockfiles. Extend its tracked inputs and tests. Exclude virtualenvs and interpreter caches explicitly; otherwise an in-repository environment can pollute freshness checks.

After retiring Scala codegen, remove its obsolete task-owned `out/codegen/scoverage` artifacts through a narrowly scoped build cleanup. The fail-closed stray-instrumentation check intentionally rejects unexpected remaining instrumentation. Never solve this by disabling that check.

### Build and tooling changes that are easy to miss

- `build.mill:272–315`: remove Python modules from `sourceStyle.modules`. Replace both `codegen.scalafixRepositories()` and `codegen.scalacOptions()` with an appropriate remaining Scala module/shared source.
- `build.mill:317–329`: update Scala coverage membership.
- `tools/check_generation.py`: keep independent double generation and provenance checks; update the generated-source subtree and preserve the four-argument CLI.
- `tools/test_generation_paths.py`: extend path/layout failure tests.
- `tools/coverage.sh`, `tools/check_coverage.py`, `tools/test_coverage_gate.py`: add equivalent Python evidence and fault tests.
- `.github/workflows/validate.yml` and `release.yml`: replace codegen's Scala compile/format/coverage selectors with explicit Python gates.
- `.github/workflows/compatibility.yml` and `real-obs.yml`: provision Python when compiling protocol transitively.
- Lock-aware CI caches: include interpreter/platform and runtime/development locks. Avoid sharing virtualenvs across operating systems.
- Preserve artifact uploads, adjusting Python JUnit/coverage paths explicitly.
- `tools/check_dependencies.py` and release reports: JVM runtime dependencies should remain unchanged; Python build dependencies need separate lock/license accounting.
- Preserve `integration.test.compile`, `site.build`, source/doc jars, package reports, and external-consumer verification.
- Update `docs/code-generation.md`, the golden README, and contributor/build/release instructions.

The existing `python3 -m unittest discover -s tools -p 'test_*.py'` command does not discover pytest-style generator tests automatically. Keep tools tests and generator tests as explicit separate gates.

## 8. Performance: evidence, not a promise

Three warm-cache runs of the existing full Scala generator were measured with no-server Mill startup included:

- Forced `codegen.run`: 2.832186, 2.252343, 2.255354 seconds; median 2.255354 seconds.
- Cached `protocol.generatedSources`: 2.597130, 1.774263, 2.157014 seconds; median 2.157014 seconds.

Host activity was uncontrolled. These are small samples, not an isolated benchmark. The cached task skips generation, so it is not the cost of Scala generation itself.

The minimal one-file Python spike had a cached-task median of 1.456228 seconds and a clean-local-output run of 7.560987 seconds. The latter included compilation/tests and two environment creations with already provisioned Python and warm shared caches. **Neither is comparable to the full Scala generator as a speedup.**

Python removes compilation of the generator's Scala implementation. It does not remove Mill's JVM startup/meta-build, dependency provisioning, or compilation of the generated Scala library.

After full parity, benchmark the same 218-file workload under matched conditions:

1. Environment provisioning from clean caches.
2. Clean generator/build outputs with warm downloads.
3. Warm forced generation, including and excluding build-launch overhead where measurable.
4. Unchanged cached build.
5. One template edit.
6. One Python-source edit versus a Scala-generator-source edit.
7. Schema edit followed by downstream Scala compilation.
8. Daemon-warm and no-server runs reported separately.

Verify output hashes on every measured variant. Retain raw samples, report medians and spread, and record toolchain/cache conditions. Do not estimate a speedup by subtracting unrelated noisy runs. Maintainability may justify the migration even if no-op build time barely changes.

## 9. Implementation plan

All tasks below are planned, not completed. Keep the active Scala generator until the explicit cutover. Use small reviewable changes; split a task further if it grows beyond one responsibility.

### T1 — Freeze compatibility evidence

Dependencies: none. Scope: small.

Likely files: new parity-test helper and baseline manifest/fixture metadata; existing golden fixtures remain unchanged.

- [ ] Record all 218 relative paths and byte hashes against the reviewed commit; retain full old output as a reproducible migration artifact.
- [ ] Map existing generator tests and generated public contracts to Python test cases.
- [ ] Keep valid-pinned parity expectations separate from new hardening cases.

Verification: run existing `codegen.test`, `protocol.test`, and `python3 tools/check_generation.py`; assert the baseline inventory count programmatically. Store the schema checksum and generator commit with the manifest.

### T2 — Prove supported-platform Python provisioning

Dependencies: T1. Scope: medium; platform support is the highest-risk gate.

Likely files: shared build trait, interpreter declaration, bootstrap instructions, focused integration fixture.

- [ ] Pin/provision an exact interpreter and assert the version; never rely on an arbitrary host `python3`.
- [ ] Reproduce/fix the native Windows virtualenv-path issue without dropping a supported platform.
- [ ] Verify interpreter mismatch and dependency-hash failures on supported CI operating systems.

Verification: minimal generated Scala compile/run and Python test run on Linux, macOS, Windows. Block production cutover until all pass. A separately reviewed Mill upgrade is allowed if needed, not bundled invisibly.

### T3 — Add cache-correct native Python module and tools

Dependencies: T2. Scope: medium.

Likely files: `build.mill`, runtime/development requirements files, `codegen/pyproject.toml`; split lock/tool configuration into its own change if necessary.

- [ ] Add the parallel Python module, shared `venv()` runner edge, tracked templates/configuration, and explicit nested-module toolchain settings.
- [ ] Lock Jinja and developer tools, accounting for native Mill default dependencies.
- [ ] Expose fresh tests, cached tests, lint, format-check, strict type-check, and report-producing commands with explicit production/test source roots and tracked configuration paths.

Verification: compile the build; test actual package-version invalidation, not only a lock comment. Verify changed imports/templates rerun dependent tasks while unrelated tasks stay cached. Introduce an error in an unimported production file and change a configuration-only lint/type rule; the appropriate gate must detect both. Record exact final task names before adding CI callers.

### T4 — First complete vertical slice

Dependencies: T1, T3. Scope: medium; split scaffolding and renderer if needed.

Likely files: CLI/input boundary, minimal raw/normalized records, renderer, one empty-request template, focused tests.

- [ ] Read the existing four CLI inputs, verify provenance, and render an empty request/response fixture.
- [ ] Keep production protocol generation on Scala while the candidate output compiles in isolation.
- [ ] Match the corresponding golden bytes and fail before writing on malformed inputs.

Verification: Python CLI from a different working directory; focused tests; generated Scala compilation. No stub output or placeholder request catalog may enter the production build.

### T5 — Port field semantics and Scala helpers

Dependencies: T4. Scope: medium, repeated as small type/field slices.

Likely files: `parsing.py`, `normalize.py`, `scala.py`, field macro, corresponding tests.

- [ ] Preserve exact types, optional/null/default behavior, renamed keys, and dotted paths.
- [ ] Port lexical/documentation behavior without Python coercion or escaping shortcuts.
- [ ] Add parser and field-combination matrices, including negative cases.

Verification: parameterized Python tests and request/response golden comparisons; compile and exercise representative generated codecs.

### T6 — Port events and enum templates

Dependencies: T5. Scope: two independent small changes after shared records stabilize.

Likely files: event/enum templates, enum normalization helper if needed, focused tests.

- [ ] Match current event and enum output, preserving unknown values and constant ordering.
- [ ] Separate parity cases from numeric-width/reference hardening cases.
- [ ] Reject unsafe expressions without `eval`/`exec`.

Verification: full family byte comparisons; generated Scala compiler/runtime checks for masks and unknown values. Compile-supported hardening fixtures separately from the pinned baseline.

### T7 — Port shared dispatch, catalog, facades, and inventory

Dependencies: T5, T6. Scope: split dispatch/catalog and facade/inventory into two changes.

Likely files: remaining complete-file templates, renderer routing, focused tests.

- [ ] Preserve 24-entry dispatch chunks, category/method names, wire dispatch, and metadata ordering.
- [ ] Produce exactly the full baseline path set and bytes, including TSV.
- [ ] Keep the runtime dependency graph and public generated signatures unchanged.

Verification: full old-versus-new parity and independent Python double generation; existing protocol/catalog/facade tests against candidate-generated sources.

### T8 — Harden owned output and CLI failures

Dependencies: T4; final verification after T7. Scope: small.

Likely files: `output.py`, CLI error boundary, filesystem/CLI tests.

- [ ] Reject duplicate, escaping, symlink, and cross-platform-colliding destinations before writing.
- [ ] Render all files first and define write-failure/fresh-directory behavior explicitly.
- [ ] Prove stale outputs disappear under Mill ownership without deleting unrelated files.

Verification: real temporary directories, injected write failure at the boundary, different-cwd invocation, malformed schema/template tests. Recheck pinned byte parity.

### Checkpoint A — Candidate is real, default unchanged

- [ ] All seven output families match the frozen baseline.
- [ ] Candidate-generated Scala compiles and protocol behavior passes.
- [ ] Input/environment invalidation works on supported platforms.
- [ ] Python lint, type checking, tests, and equivalent coverage evidence are available.

Do not proceed by updating goldens around unexplained differences.

### T9 — Add Python coverage and fail-closed gate tests

Dependencies: T3–T8. Scope: separate checker and integration changes.

Likely files: Python coverage configuration/checker, `tools/check_coverage.py`, `tools/test_coverage_gate.py`, coverage runner.

- [ ] Enforce exact complete Python statement/branch evidence and inventory/freshness validation.
- [ ] Track templates, TOML, locks, and interpreter declarations without hashing virtualenvs.
- [ ] Add negative gate tests and retain existing Scala coverage behavior.

Verification: deliberately omit a production module/report, change a template/lock after measurement, and introduce an uncovered branch; each must fail. A clean measurement must pass.

### T10 — Cut over the generation task

Dependencies: Checkpoint A and T9. Scope: medium.

Likely files: `build.mill`, `tools/check_generation.py`, `tools/test_generation_paths.py`.

- [ ] Replace only the generator process edge with the verified Python runner and owned `scala` subtree.
- [ ] Preserve all tracked schema inputs, inherited generated roots, and failing-task behavior.
- [ ] Remove Python codegen from Scala-only sourceStyle/coverage selectors and repair shared Scalafix references.

Verification: clean and cached `protocol.generatedSources`; generation parity; protocol/downstream tests; actual lock-change invalidation; generation-path tooling tests. Do not leave the normal build dependent on both generators.

### T11 — Wire CI, packaging, and documentation

Dependencies: T10. Scope: split workflow provisioning, release gates, and documentation into separate changes.

Likely files: validation/release/compatibility/real-OBS workflows, dependency accounting, `docs/code-generation.md`, contributor docs, golden README.

- [ ] Provision/pin Python for every workflow that transitively generates protocol sources; lock cache keys and upload Python reports.
- [ ] Keep Scala compile/format/test/docs/package/consumer checks, with Python replacements for retired generator checks.
- [ ] Document CLI, environment bootstrap, lock updates, output ownership, templates, and troubleshooting.

Verification: all supported-platform CI jobs; `integration.test.compile`, `site.build`, dependency/package reports, published source/doc contents, external consumer smoke. Fix the pre-existing ObsConfig issue separately before calling the release pipeline green.

### T12 — Retire Scala codegen and publish measurements

Dependencies: T11 and all cutover gates green. Scope: separate retirement and benchmark changes.

Likely files: old Scala generator/tests, temporary comparison wiring, benchmark/report artifacts, this status document.

- [ ] Remove retired Scala implementation and temporary duplicate wiring; retain fixture and semantic coverage in Python/Scala consumer tests.
- [ ] Clean only obsolete task-owned Scoverage artifacts and confirm stray-instrumentation detection still works.
- [ ] Measure matched full-generator workloads and publish raw conditions/results without extrapolating from the spike.

Verification: clean checkout build with no retired generator artifacts; complete test/coverage/release gates; full byte map unchanged; dependency graph contains no Python runtime requirement for library consumers.

### Checkpoint B — Migration complete

- [ ] Production protocol generation uses only Python/Jinja.
- [ ] Full output parity and public/wire contracts are verified.
- [ ] Supported platforms pass; no CI coverage or release gate was removed without replacement.
- [ ] Documentation and locks match the actual commands and environment.
- [ ] Performance claims are supported by matched full-catalog measurements.

### Parallel work and ownership

After T1–T3 establish shared records/build conventions:

- One owner handles parser/normalizer/helper contracts.
- A second handles complete-file templates once context types are stable.
- A third handles parity/negative tests and quality-gate fault tests.
- One integrator owns `build.mill`, workflow cutover, and final full-catalog verification.

Do not let independent agents redefine field nullability or edit the same build/lock files concurrently. Keep generated-output review and compiler-backed checks as integration gates.

### Rollback

Before T10, Scala remains the default, so candidate failure does not interrupt the production build. Keep the reference output and old implementation available until all replacement gates pass.

If cutover fails, restore the reviewed generation edge and its matching gate configuration as one intentional change. Do not leave Python-generated leftovers in the Scala source root. Regenerate under Mill ownership and rerun parity/downstream checks. Do not reset or discard unrelated working-tree changes.

## 10. Wider library review: implications, not extra migration scope

The Python migration should remain build-time only. Preserve the useful protocol/core/backend module split, direct-style API, scoped resource ownership, and single-owner session state.

Confirmed issues from the concurrent review, recorded here so a green generator test suite is not mistaken for a release-ready library:

1. **High priority: external configuration construction fails.** `ObsConfig.scala:125` restricts the companion; consumer smoke fails at normal `ObsConfig(...)` construction. Make the public construction surface accessible while keeping helper members internal. Test from a neutral external package.
2. **High priority: request budget excludes registration wait.** `ObsSession.scala:159–179` awaits actor registration before starting `timeoutOption(budget)`, despite the registration-plus-response budget contract. Use one deadline spanning both stages, preserving owner-checked cancellation and cleanup. Test an actor stalled before registration; the existing externally imposed timeout test does not establish the per-request contract.
3. **Medium priority: public unchecked batch decoder.** `BatchCall.scala:22–41` exposes `BatchCodec.decode`, whose tuple recursion uses `results.head`/`tail` without validating arbitrary caller input. Normal `typedBatch` validates its input path; do not claim ordinary calls are broken. Prefer internal decoder operations while preserving public typeclass evidence, or explicitly define a validated public decoder contract.
4. **Architecture opportunity, not measured regression:** envelope parsing and event dispatch work share the session actor. Consider decoding envelopes on the existing reader worker while preserving order, error accounting, and actor ownership of mutable state. Benchmark event-heavy traffic before adding parallel dispatch or extra queues.

For larger architecture cleanup, split responsibilities around actual invariants: pending-request lifecycle, subscription registry, and wire decoding. Keep a coordinating actor rather than replacing it with many independently synchronized objects. Use transport adapters and factory methods where they already represent real variation. Do not add inheritance, builders, or generic repositories merely to increase the pattern count.

These runtime fixes are independent of Python parity and should be reviewed/tested separately. The full Scala suite passing does not invalidate the consumer and deadline findings.

## 11. Evidence and references

### Local execution artifacts

These scratch paths are session evidence, not durable repository dependencies. Scratch storage may be pruned; promote needed migration evidence into reviewed fixtures or CI artifacts during T1.

- Mill/Python executed spike: `/home/worxbend/.hermes/cache/scratch/mill-python-1.1.10-spike/`
- Spike report: `SPIKE_REPORT.md` beneath that directory.
- Working isolated build: `build.mill` beneath that directory.
- Native cache reproduction: `evidence/native-runner-cache-bug.txt` and `evidence/dependency-change-native-runner.log`.
- Fixed invalidation: `evidence/dependency-change-fixed.log`.
- Other spike evidence: `evidence/interpreter-mismatch.log`, `evidence/lock-hash-mismatch.log`, `evidence/snapshots.json`, `evidence/runs.jsonl`, `evidence/summary.json`.
- Scala baseline: `/home/worxbend/.hermes/cache/scratch/obs-codegen-baseline-n4jbklus/baseline.json`.
- Scala test log: `/home/worxbend/.hermes/cache/scratch/obs-review-tests.log`.
- Tools test log: `/home/worxbend/.hermes/cache/scratch/obs-review-tools.log`.
- Generation log: `/home/worxbend/.hermes/cache/scratch/obs-review-generation.log`.
- Consumer failure: `/home/worxbend/.hermes/cache/scratch/obs-review-consumer.log`.

Reproducing the minimal spike uses the repository launcher with the spike as the working directory:

```sh
/home/worxbend/Projects/Github/obs-websocker-client/mill --no-daemon --ticker false -j 1 protocol.run
/home/worxbend/Projects/Github/obs-websocker-client/mill --no-daemon --ticker false -j 1 codegen.test
/home/worxbend/Projects/Github/obs-websocker-client/mill --no-daemon --ticker false -j 1 codegen.test.testCached
```

Run these separately. Arguments after a runnable command can be consumed as application arguments rather than independent task selectors.

### Official sources

Version-matched Mill source was consulted and its relevant integration executed:

- [PythonModule 1.1.10](https://github.com/com-lihaoyi/mill/blob/1.1.10/libs/pythonlib/src/mill/pythonlib/PythonModule.scala): interpreter, venv, runner, sources, default tool dependencies.
- [PipModule 1.1.10](https://github.com/com-lihaoyi/mill/blob/1.1.10/libs/pythonlib/src/mill/pythonlib/PipModule.scala): tracked requirement inputs and install arguments.
- [TestModule 1.1.10](https://github.com/com-lihaoyi/mill/blob/1.1.10/libs/pythonlib/src/mill/pythonlib/TestModule.scala): unittest/pytest integration, cached tests, placeholder result type.
- [Native minimal Python example](https://github.com/com-lihaoyi/mill/blob/1.1.10/example/pythonlib/basic/1-simple/build.mill).
- [Native tracked requirements example](https://github.com/com-lihaoyi/mill/blob/1.1.10/example/pythonlib/dependencies/2-pip-requirements/build.mill).
- [Tagged Python support documentation](https://github.com/com-lihaoyi/mill/blob/1.1.10/website/docs/modules/ROOT/pages/pythonlib/intro.adoc).
- [Jinja API](https://jinja.palletsprojects.com/en/stable/api/) and [template whitespace behavior](https://jinja.palletsprojects.com/en/stable/templates/#whitespace-control).
- [Python dataclasses](https://docs.python.org/3/library/dataclasses.html) and [JSON parsing](https://docs.python.org/3/library/json.html).
- [pytest fixtures](https://docs.pytest.org/en/stable/how-to/fixtures.html), [parameterization](https://docs.pytest.org/en/stable/how-to/parametrize.html), and [temporary paths](https://docs.pytest.org/en/stable/how-to/tmp_path.html).

Some hosted documentation fetches failed; official raw repository documentation was used as a fallback. The Linux integration proof is release-specific. Revalidate these assumptions when changing Mill, Python, Jinja, or the test toolchain.
