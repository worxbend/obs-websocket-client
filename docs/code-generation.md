# Code generation

The `codegen` Mill module is an offline schema compiler. It has no runtime dependency relationship with the published client. Its implementation lives under `com.worxbend.obs.websocket.client.codegen`.

## Responsibilities

- `Generate` owns CLI arguments, file reads, checksum verification, and writes. Its fully qualified entrypoint is referenced by `build.mill`.
- `schema` contains JSON input records, parsing, and schema normalization. Validation resolves naming collisions, supported types, nullable overrides, enum values, and request categories before rendering.
- `model` holds immutable normalized definitions shared by the generator and templates.
- `CodeGenerator` maps normalized definitions to deterministic output filenames and template contexts.
- `templates` contains a renderer for each output family and focused shared source fragments. Templates receive typed contexts and use Scala multiline interpolation.

The schema layer does not depend on templates. Templates do not read files or decode JSON. Internal declarations remain `private[codegen]`; the generated protocol API is a separate contract.

## Editing a template

Choose the relevant output family: request/response, event, enum, event dispatch, catalog, request API, or inventory. Keep source layout visible in its multiline string. Prepare variable sections as named values instead of assembling the file with concatenated lines. Put schema decisions in normalization, and repeated Scala syntax in a focused shared renderer.

Interpolation does not automatically indent subsequent lines of a fragment. Each multiline fragment must own its output indentation and document whether it includes a final newline. Insert complete blocks at the template margin without adding indentation to only their first line. Inline fragments such as parameter lists contain no newline. Preserve blank lines and final newlines explicitly; do not trim rendered files.

Keep literal escaping separate from Scaladoc escaping. JSON property names must retain their wire names even when Scala members are renamed. Optional, nullable, and dotted fields have distinct codec behavior. Decoder dispatch maps remain chunked into 24 entries to avoid JVM method-size limits.

## Verification and fixtures

`codegen/test/resources/golden` contains a small input schema, overrides, provenance, and complete outputs captured before the template refactor. The generator test compares the entire output map, including filenames and whitespace. The fixtures cover all seven output families, empty payloads, optional dotted fields, required nullable fields, renamed members, enums, and documentation escaping. Existing behavioral tests cover additional schema failures and wire-shape cases.

Run:

```sh
./mill codegen.test
./mill protocol.test
python3 tools/check_generation.py
tools/coverage.sh
```

`check_generation.py` verifies the pinned checksum, compares two independent generations, and checks that Mill-managed generated sources agree. For structural refactors, also capture the old generator's full output in a temporary directory and compare every file after the change. Reproducibility alone cannot prove equivalence to the old implementation.

When intentionally changing output, review the generated API and full fixture diff before updating expected files. Never refresh fixtures just to make a failing comparison pass. A schema upgrade must also update its reviewed provenance and overrides. Normal generation never fetches an upstream branch.

The [design decision](ideas/codegen-templates.md) records why Scala interpolation was selected. Test and coverage outcomes must be measured independently; this document does not establish a passing coverage result.
