# Changelog

## Unreleased

- Add Scala 3 protocol, core, and sttp modules for OBS WebSocket 5.x/RPC 1.
- Generate typed bindings from the pinned 147-request/60-event catalog.
- Add scoped authentication, bounded request/event dispatch, typed batching, raw extensions, and opt-in reconnect.
- Add a read-only Tapir sample with PureConfig/HOCON, Swagger, and local binding.
- Add deterministic fault tests, exact coverage enforcement, source/API artifacts, isolated consumer checks, and documentation tooling.
- Hardening: fix `server.run` Netty lifecycle leak, consolidate the error taxonomy (non-fatal `MessageTooLarge`, `UnsupportedMessage`, `InternalError`), make capability gating and raw escape hatches coherent across single/batch APIs, unify duplicate raw-event types, and harden the generator (override validation, duplicate detection, literal escaping, upstream-linked Scaladoc).
- **Breaking refactor:** subscription `next()` reads now return tri-state `Next.Item` / `Next.Failed` / `Next.Ended` instead of overloading `Left(ObsError.Closed)` for clean end-of-stream; clean source end no longer error-closes sampled streams. `Field`'s companion provides a given `Conversion[A, Field[A]]`, so optional request fields accept plain values.

### Review hardening: transport deduplication, correctness, and API parity (2026-10-05)

#### Changed

- **Transport deduplication:** the four transports' duplicated frame loops are extracted into `transport.AbstractObsTransport` over the neutral `transport.TransportFrame` ADT in core; adapters keep only frame/error mapping. A cross-backend contract suite (`BackendContractSuite` in `sttp/test` with per-backend subclasses) replaces the cloned client/reconnecting suites and pins shared wire behavior, including close-code preservation, clean-close surfacing, and a real reconnect-after-drop case per backend.
- **Post-abort correctness guard:** after a transport abort, a non-fatal failure of an in-flight operation (for example a foreign `CancellationException`) reports `ObsError.Transport` instead of flipping an already-computed retryable `Timeout` into a terminal defect.
- **API parity:** new `OkHttpReconnectingObsClient` wrapper; `JdkClientOptions` moved from the sttp module to core's `transport` package (pre-release package move), giving zio/fs2 `connect` proxy/TLS parity; `transport.okhttp.OkHttpOptions` type alias so okhttp consumers need not import the sttp module; `OkHttpClientOptions`/`JdkClientOptions` redact only sensitive fields and render innocuous deadlines.
- **Robustness:** bounded 5s Pekko actor-system termination with unique monotonic system names; fs2 dispatcher release registered at allocation; zio/fs2/pekko validate configuration before allocating clients/runtimes/systems.
- **Tooling:** consumer smoke now runtime-initializes all five backend bridges against a closed port; the redundant `publishLocal` CI step is removed; okhttp/zio/fs2 declare their direct-import dependencies explicitly (versions pinned to sttp 4.0.27's transitive resolution).

### Backend modules (ADR-005, 2026-10-05)

#### Added

- New published backend artifacts: `obs-websocket-client-okhttp` (OkHttp sync adapter over `SttpObsClient.withBackend`), `obs-websocket-client-zio`, `obs-websocket-client-fs2`, and `obs-websocket-client-pekko` (bridged async adapters running a ZIO runtime, a cats-effect `IORuntime`/`Dispatcher`, or a private `ActorSystem` internally). Every adapter implements the blocking `ObsTransport` seam and presents the same `ObsSession` API, with per-adapter `connect`/`withBackend` entrypoints and reconnect wrappers delegating to core.

#### Changed

- **Breaking package moves (pre-release):** the reconnect family (`ReconnectPolicy`, `ReconnectTiming`, `ReconnectDecision`, `ReconnectNotice`, `ConnectionGeneration`, `ReconnectConnector`), `HandshakeHeaders`, and the text-fragment/byte-limit assembler moved from `transport.sttp` to core (`reconnect.*` and `transport.*` packages). `ReconnectingObsClient.withConnector` became `com.worxbend.obs.websocket.client.reconnect.Reconnect.run` with the identical parameter list; `ReconnectingObsClient.run` keeps its signature.
- CI, coverage, packaging, consumer smoke, documentation site, and release workflows now enumerate all seven library artifacts.

### Release-readiness round (2026-10-05)

#### Added

- Opt-in `SttpOptions.readIdleTimeout` (default `None`): any receive stalled past the deadline — including a stall between the fragments of one message — fails with a retryable `ObsError.Timeout` and destroys the connection, so half-open connections (lost FIN, NAT timeout) engage reconnect classification instead of stalling subscriptions silently. The default preserves previous behavior.

#### Changed

- Reconnect retry budget and backoff delay progression now restart whenever a generation's callback completes; only consecutive attempts that fail before reaching the callback consume the cumulative budget.
- Diagnostics subscriptions now receive the concrete session failure once before completion, instead of a silent close.
- Defects inside the client's own reader/writer workers now fail the session with a non-retryable `ObsError.InternalError` carrying the cause (previously misreported as retryable `Transport`). User key-function exceptions in `SampledSubscription`/`withLatestBy` terminate the stream with `Next.Failed(InternalError)`. Callback exceptions from the scoped `connect`/`withTransport` APIs propagate raw and are never wrapped in `ObsError`; expected failures stay inside the returned `Either`.
- OBS close codes during identification: 4010 now maps to `IncompatibleProtocol` and 4011 to the `Authentication` family (4009 was already mapped).
- `ObsConfig.toString` now shows the URI — validation forbids credentials in it, and unvalidated copies are stripped defensively — while the password provider stays redacted.
- `Catalog.roundTripResponse` is now `private[protocol]`; it was public test-only surface.

#### Fixed

- `VolumeMultiplier.decibels` saturates at the documented 26 dB ceiling, so every valid multiplier in [0, 20] converts (previously multipliers above ≈19.95 failed).
- Requests rejected at registration no longer count in `SessionStats` completed/failed counters.
- Code generator hardened: field-name collisions introduced by payload renames are rejected loudly, and hostile event-mask expressions are validated against sibling identifiers with the offending mask named in the failure.

#### Performance

- Measured with JMH (Temurin 25.0.3; full setup and numbers in [bench/README.md](bench/README.md)): the allocation-free UTF-8 byte-counting used on the message path is 7–40× faster than the replaced encoder-plus-scratch implementation, with zero allocation versus ~4.3 KB/op.
- `JsonValue` decode now uses builders with duplicate detection via a mutable hash set: the initial builder rewrite measured ~17–22% slower than the legacy codec, was caught by the benchmark, and the reworked version measures at parity or better on all document sizes (ratios 0.97–1.04 across two runs) at a ~3.5–7.4% residual allocation overhead. History and numbers in bench/README.md.

#### Build & CI

- logback-classic 1.6.4 → 1.6.5 (only stale pin from the 2026-10-04 dependency-currency check).
- `validate.yml` and `compatibility.yml` cache Mill outputs keyed by OS and toolchain/dependency inputs.
- Real-OBS workflow timeout raised from 20 to 40 minutes.
- `release.yml` provisions Temurin 25.0.3 via setup-java and adds a separate tag-gated GitHub Release job with generated notes; its failure does not fail the publication.
- Dead actionlint configuration removed.
- `build.mill` now asserts that Mill itself runs on Java 25 and points at the wrapper when it does not.
- Documentation aligned with the current implementation; interim planning artifacts removed.

This entry describes local implementation, not a published release.
