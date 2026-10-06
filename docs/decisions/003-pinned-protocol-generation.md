# ADR-003: Generate bindings offline from a pinned schema

## Status

Accepted; recorded 2026-10-04.

## Date

Recorded 2026-10-04.

## Context

The OBS request and event catalog is large and evolves independently of this client. Upstream field descriptions can be incomplete, and a development schema can describe fields absent from older servers. Compilation alone cannot prove wire compatibility.

## Decision

Check in the schema, explicit overrides, and provenance with a reviewed revision and SHA-256 checksum. Run the generator offline as a Mill source task, write outputs under `out/`, and verify deterministic output. Keep envelope handling and session logic handwritten. Preserve missing/null distinctions and exact numeric values, expose unknown values where possible, and keep raw request/event escape hatches.

Include generator logic and generated production bindings in the exact statement/branch coverage gate. Track real OBS compatibility separately through explicit versions and operations; do not infer full compatibility from generated model counts or coverage percentages.

## Alternatives considered

Hand-maintaining the entire catalog duplicates schema work and increases drift. Fetching a moving upstream branch during compilation makes identical checkouts build differently. Treating every numeric field as an integer or collapsing null and omission loses wire semantics. Broadly excluding generated code from coverage would weaken the measurement rules this project enforces.

## Consequences

Ordinary builds do not need an upstream schema download. Upgrades require reviewing schema changes, override validity, generated diffs, fixtures, and compatibility evidence. Loosely described nested object fields remain `JsonObject`; typed outer models do not imply fully typed nested payloads. Schema-derived output licensing remains a release gate, independently of the first-party MIT license.

## Evidence

See [provenance.json](../../protocol-spec/provenance.json), [main.py](../../codegen/src/main.py), [Field.scala](../../protocol/src/com/worxbend/obs/websocket/client/protocol/Field.scala), and [check_generation.py](../../tools/check_generation.py). The [compatibility matrix](../compatibility.md) distinguishes tested behavior from catalog scope.
