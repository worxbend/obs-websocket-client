# Architecture decisions

These records capture decisions already agreed in [PLAN.md](../PLAN.md) and implemented in the repository. The record date is 2026-10-04; it is not an assertion about when the original decision was made. Alternatives describe trade-offs, not an invented history of benchmark results or team votes.

| Record | Decision | Status |
| --- | --- | --- |
| [ADR-001](decisions/001-library-boundaries.md) | Separate protocol, session core, transport, and applications; keep Mill and the direct-style stack | Accepted, recorded retrospectively |
| [ADR-002](decisions/002-scoped-session-and-reconnect.md) | Own each session through a callback, confine state to an actor, and retry only with fresh generations | Accepted, recorded retrospectively |
| [ADR-003](decisions/003-pinned-protocol-generation.md) | Generate bindings offline from pinned inputs and distinguish catalog coverage from server compatibility | Accepted, recorded retrospectively |
| [ADR-004](decisions/004-additive-peer-features.md) | Add peer-inspired APIs and observability while preserving scoped ownership and raw compatibility | Accepted design |
| [ADR-005](decisions/005-backend-modules.md) | Publish one artifact per WebSocket backend, confining effect runtimes to opt-in adapters | Accepted design |

Read the [current architecture](architecture.md) for module, handshake, dispatch, and reconnect diagrams. Existing API behavior is documented in [requests](requests.md) and [events](events.md).
