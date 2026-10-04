# Peer comparison and feature expansion

This work adds convenience and observability to the existing OBS WebSocket 5 client. It does not change the protocol catalog: the pinned schema already has the same 147 request names and 60 event names found in the inspected goobs revision. The meaningful gaps were typed nested values, ergonomic APIs, connection controls, and common workflows.

The comparison was made on 2026-10-04 against [goobs](https://github.com/andreykaipov/goobs/tree/872fb99ae9f189ee1569970f5c07f713b7eecaef), [tinodo/obsclient](https://github.com/tinodo/obsclient/tree/ba06dd57186404eed0ab558a5c16750a2ea75587), and [maths22/obs-websocket-java](https://github.com/maths22/obs-websocket-java/tree/5ddc119cd2a429bc4e715aa09aa8e79fee3e0aec). These are source comparisons, not performance benchmarks. The Java client targets the legacy protocol, so its convenience methods are inspiration rather than modern wire compatibility evidence.

## Agreed implementation

| Capability | Peer inspiration | Implementation in this project |
| --- | --- | --- |
| Category request APIs | goobs generated service groups | Generate all 147 named methods in category facades, sharing the existing request engine |
| Typed nested values | goobs typedefs and .NET response models | Add lossless validated model views; retain raw objects and unknown future fields |
| Typed event selection | .NET event handlers, goobs event types | Generate selectors for the 60 known events; expose scoped typed subscriptions |
| Per-call timeout | goobs request timeout, .NET batch timeout | One exchange budget covering registration and response waiting, including raw requests and batches |
| Typed plus raw result | goobs raw response access | Decode an envelope from one received response, retaining raw data even if typed decoding fails |
| Transport tuning | goobs dialer and handshake headers | Validated headers, owned-client TLS/proxy options, and bounded writes with connection abort |
| Diagnostics | goobs logging, .NET traffic counters | Bounded metadata streams and session counters, without payloads or user callbacks on the dispatcher |
| Readiness retry | .NET explicit NotReady recovery | Opt-in bounded retries for reviewed read operations and startup discovery; only server code 207 |
| Resource selectors | .NET name/UUID overloads | Smart constructors for scene/input selectors and scene-item IDs |
| High-frequency events | .NET throttling | Opt-in latest-per-key windows with bounded keys and explicit loss counters |
| Workflow helpers | .NET geometry/screenshots and Java browser settings | Bounded screenshot decoding, Qt projector geometry, volume units, and browser-source patches |
| Compatibility fixtures | Peer models plus protocol evolution | Synthetic old/modern/future payload shapes, with absent UUID and unknown-key cases |

The existing raw request and event APIs remain available. There is no new mandatory library module or effect runtime. Generated APIs use the same capability checks, correlation, validation, deadlines, and ownership rules as direct requests.

## Design and review workflow

Three agents first explored alternatives and agreed on contracts across protocol, core, and transport. They implemented separate modules, then reviewed one another's code. The coordinating agent owns workflow helpers, integration, documentation, and final validation. Builds are serialized to avoid competing generated outputs.

The review focuses on cancellation, bounded memory, privacy, version compatibility, and source compatibility. In particular, interrupting a timeout wait is insufficient for a non-interruptible foreign write: the transport must abort the connection before its scope joins that writer.

## Acceptance gates

The feature work is accepted only after compilation with warnings as errors, deterministic generation, targeted behavior tests, the complete production test suite, exact statement and branch coverage, compiled documentation snippets, and the offline documentation build. Coverage includes generated code and workflow helpers. An authored fixture or a passing mock test is not a live OBS compatibility result.

No claim of superior throughput or latency is made without comparative measurements. Automatic replay of uncertain writes, implicit loss of control events, and retries of authentication failures are deliberately outside this design. No new release or deployment is implied by changes in the working tree.

See [requests](requests.md), [events](events.md), [recipes](recipes.md), and the [architecture](architecture.md) for the resulting API and ownership model.
