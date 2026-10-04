# ADR-004: Add peer-inspired conveniences above the existing session engine

Date: 2026-10-04

Status: Accepted design; validation is reported separately from implementation.

## Context

The peer comparison found no missing request or event names relative to the inspected goobs catalog. It did find useful higher-level models, category methods, transport settings, observability, and workflow helpers. The project already requires scoped session ownership, bounded queues, raw extension support, and no automatic replay of uncertain operations.

## Decision

Generate category facades and event selectors from the same pinned schema as the existing bindings. Add typed nested views as lossless projections instead of replacing raw generated signatures. Keep per-call options, diagnostics, readiness recovery, and sampling in core. Keep HTTP headers, TLS/proxy configuration, and physical write deadlines in the sttp module. Keep pure data transformations in `protocol.workflows`.

Use one received response for typed and raw inspection. Deliver diagnostics through bounded pull streams. Sample events only when the caller explicitly requests it, outside the session actor. Retry only reviewed read operations after an explicit NotReady response, with a finite total budget. Abort timed-out foreign writes before waiting for worker cleanup.

## Alternatives considered

Replacing every generated object with a guessed nested model would break existing users and make unknown server additions harder to retain. New convenience methods that bypass the request engine would duplicate capability and failure handling. Arbitrary logging callbacks on the dispatcher would let slow user code stall protocol work. Automatic mutation retries cannot establish whether an earlier write executed.

## Consequences

Existing raw APIs remain useful for plugins and newer OBS versions. Typed views require callers to handle decode failures explicitly. Diagnostics can drop metadata when their bounded buffer fills, with an observable counter. Event sampling can suppress intermediate states and is unsuitable for workflows that require every control event. TLS and proxy settings apply only when this library owns the JDK HTTP client; caller-owned backends retain their own configuration.

The [feature comparison](../feature-expansion.md) records scope and acceptance gates. Synthetic version fixtures and deterministic peers complement, but do not replace, isolated real OBS testing.
