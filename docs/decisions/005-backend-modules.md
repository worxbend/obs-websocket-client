# ADR-005: Publish one artifact per WebSocket backend, with effect runtimes confined to opt-in adapters

Date: 2026-10-05

Status: Accepted design; validation is reported separately from implementation.

## Context

Consumers need a choice of WebSocket backend: the existing JDK `HttpClient` sync backend,
an OkHttp sync backend, and the streaming backends sttp exposes for ZIO
(`ZioWebSockets`), fs2 (`Fs2WebSockets`), and Pekko (`Flow`). ADR-001
originally ruled out Cats Effect, ZIO, and any effect-polymorphic API outright. That
rule exists to keep the session engine direct-style and Ox-owned; it does not need to
forbid optional adapter artifacts that pull an effect runtime in transitively.

## Decision

Split the library into independently published backend artifacts:

- `obs-websocket-client-protocol` — generated models and catalog (unchanged).
- `obs-websocket-client-core` — Ox session engine plus the blocking `ObsTransport`
  seam (unchanged shape; gains the backend-agnostic machinery listed below).
- `obs-websocket-client-sttp` — JDK `HttpClient` sync backend (existing).
- `obs-websocket-client-okhttp` — sttp `okhttp-backend` sync adapter; thin module over
  `SttpObsClient.withBackend` with its own owned-client entrypoint (implemented).
- `obs-websocket-client-zio`, `obs-websocket-client-fs2`, `obs-websocket-client-pekko` —
  adapters that implement the blocking `ObsTransport` over the respective sttp
  streaming backend (all three expose a monadic `sttp.ws.WebSocket[F]`, with `F` being
  `Task`, `IO`, and `Future` respectively), running a ZIO `Runtime`, a cats-effect
  `IORuntime` plus `Dispatcher`, or a private Pekko `ActorSystem` internally. The Ox
  session engine and the public direct-style API are reused unchanged on every backend
  (all implemented).

Backend-agnostic machinery moves from the `transport.sttp` package into `core` so the
async adapters do not depend on the JDK sync backend: the reconnect family
(`ReconnectPolicy`, `ReconnectTiming`, `ReconnectDecision`, `ReconnectNotice`,
`ConnectionGeneration`, `ReconnectConnector`, and the generic retry loop),
`HandshakeHeaders`, and a shared text-fragment/byte-limit assembler used by every
transport. This is a source-breaking package move, acceptable while the only published
version is `0.1.0-SNAPSHOT`.

Every new module meets the same gates as the existing ones: 100% statement and branch
coverage, warnings-as-errors, Scalafix/Scalafmt checks, and inclusion in
the release, consumer-smoke, and API-site module enumerations. Documented behavioral
differences discovered during implementation (error mapping for upgrade rejections,
framework-level ping handling and close-code surfacing on Pekko, and effect-thread
teardown characteristics) are recorded in `docs/guides/backends.md`.

## Alternatives considered

Effect-native session facades (Task/Stream/Flow-shaped APIs per backend) would suit
effect-ecosystem users better but require re-implementing session correlation,
subscription broadcast, and the 100% coverage gate once per effect system. Bridging the
streaming backends into the single Ox session engine ships all backends with identical
semantics now; effect-native facades remain a possible follow-up phase behind the same
artifacts. Keeping the reconnect machinery in `sttp` would force the async adapters to
depend on the JDK sync backend artifact, contradicting the self-contained goal.

## Consequences

Core, protocol, sttp, and okhttp carry no effect-system dependencies; the zio/fs2/pekko
artifacts pull their runtimes transitively and are strictly opt-in. The bridged async
transports present the blocking `ObsSession` API rather than effect-native types, which
must be documented so ZIO/fs2/Pekko users are not surprised. The package name
`transport.zio` shadows the upstream `zio` package, so that module uses explicit
`_root_.zio` imports, following the existing `transport.sttp` convention. `transport.fs2`
and `transport.pekko` needed no `_root_` imports in practice: neither module references
the shadowed root package it shares a name with.

Post-implementation review refined the design further:

- The four transports' duplicated frame loops were extracted into
  `transport.AbstractObsTransport` over the neutral `transport.TransportFrame` ADT in
  core; each adapter keeps only its frame and error mapping. The loop includes a
  post-abort guard: once a transport aborts, a non-fatal failure of an in-flight
  operation reports `ObsError.Transport` instead of flipping an already-computed
  retryable result into a defect. This guard is part of the transport contract.
- The `private[client]` cross-artifact seam now spans `TransportFrame`,
  `AbstractObsTransport`, `MessageAssembler`, `HandshakeHeaders`, `JdkClientOptions`,
  and the `reconnect.*` family. These are internal APIs shared across independently
  published artifacts, so the artifacts release in lockstep and the seam is treated as
  binary-internal, never as public surface.
- `JdkClientOptions` moved from the sttp module into core (`transport` package) so the
  zio and fs2 adapters offer the same proxy/TLS knobs for their owned JDK clients;
  pekko is deliberately excluded (Pekko-native backend).
- A per-connection `ActorSystem` is a real cost on the Pekko adapter: systems get
  unique monotonic names and a bounded 5-second termination wait, and the guide
  recommends grouping work inside one connection callback.
- Each adapter publishes a reconnect wrapper (`OkHttpReconnectingObsClient` completes
  the set), and a shared cross-backend contract suite in `sttp/test` pins the wire
  behavior of every adapter, including the documented per-backend divergences
  (upgrade-rejection wording, clean-close surfacing of 1005 versus synthetic 1000, and
  pekko-http's restricted close-code validity set).
