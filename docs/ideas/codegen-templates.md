# Readable protocol generation

## Problem statement

Make the offline OBS generator easy for Scala contributors to change by separating input handling, schema policy, and source layouts.

## Recommended direction

Use typed Scala contexts and multiline interpolation, with one template per output family. Keep the existing Mill module and `Generate.main` entrypoint. Parsing, normalization, and rendering form an explicit pipeline; filesystem effects belong to the runner. Implementation types remain package-private.

Scala interpolation gives compiler-checked context references and source-shaped layouts without a template compiler or runtime dependency. Paiges would be useful for width-aware pretty printing; this refactor preserves fixed layouts. External template engines would introduce resource loading and another validation surface without a current requirement for separately editable templates.

## Assumptions and validation

- Fixed layouts remain sufficient: preserve every byte of the pinned catalog during the refactor.
- Scala contributors maintain templates: make layouts directly readable and document fragment indentation.
- Generated APIs and wire semantics remain unchanged: compile generated sources and run protocol tests plus the repository's exact coverage gate.
- Fixtures must detect output drift independently: capture complete small-schema outputs before replacing the generator.

## Scope

Separate the CLI, JSON parser, schema normalizer, immutable generation models, and code generation orchestration. Add request, event, enum, event-dispatch, catalog, request-API, and inventory templates with focused shared rendering helpers. Document responsibilities, context contracts, and fixture maintenance.

## Not doing

- A general template engine or full Scala syntax tree: neither is needed for the fixed output families.
- Dependency or toolchain changes: preserve the current Mill and Scala contract.
- Generated API changes, protocol fixes, or formatting changes: review these separately from restructuring.
- Runtime effect-system changes: the generator remains synchronous and offline.

## Decision

The user approved Scala interpolation and implementation on 2026-10-05. Validation results are reported separately from this design decision.
