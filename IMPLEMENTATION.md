# Implementation and verification

Updated 2026-10-05. This report distinguishes implemented behavior, local evidence,
and remaining release gates. No external library release occurred. Pages is configured for deployment from validated `main` builds.

## Implemented

The Mill project contains protocol, core, sttp, generator, examples, server, and
opt-in integration-test modules. The reusable dependency direction is
`protocol <- core <- sttp`. Tapir and PureConfig belong only to the sample server.

The pinned OBS schema generates 147 requests, 60 events, seven enum groups, codecs,
and an inventory. The generator is offline and deterministic. JSON preserves
missing/null distinctions, unknown fields in raw objects, unknown event/enum values,
and accepted numbers exactly within explicit digit/depth/message-size bounds.

The client implements authentication, capability discovery, bounded correlation and
writing, independent broadcast subscriptions, deadlines, typed errors, typed batches,
reidentify, raw extension APIs, and scoped shutdown. Opt-in reconnect creates fresh
generations, refreshes credentials, reports gaps, resets its retry budget and backoff
after each healthy generation, and never transfers pending requests.

The peer-inspired expansion adds 14 category facades for all 147 requests, selectors
for all 60 events, 12 lossless nested model views and 14 response/event projections,
validated resource references, per-call budgets, raw-plus-typed response envelopes,
bounded diagnostics/counters, optional latest-per-key sampling, and explicit read-only
NotReady recovery. The sttp adapter adds write deadlines, optional read-idle liveness
detection, validated handshake headers, and owned-client TLS/proxy settings. Pure helpers
cover screenshots, Qt projector
geometry, browser-source patches, and audio volume units. See the
[feature comparison](docs/feature-expansion.md) and [ADR-004](docs/decisions/004-additive-peer-features.md).

The sample exposes health, read-only OBS version discovery, Swagger assets and OpenAPI.
Typed PureConfig/HOCON settings support environment overrides and redacted secrets.

## Executed local evidence

- 2,711 Scala tests pass: codegen 29, protocol 2,455, core 126, sttp 64, examples 7, server 30.
- Fresh Scoverage measurements: 11,350/11,350 statements and 814/814 branches, with no production exclusions. The clean gate resets measurements and binds reports to the source and instrumentation fingerprints.
- 22 Python tests verify coverage rejection (including swapped reports and altered statement inventories), import safety, and documentation rendering.
- The upstream schema checksum verifies; two offline generations and the Mill output match byte-for-byte across 218 files (217 Scala files plus inventory).
- All 26 upstream skill files still match their recorded Git blob hashes.
- Real RFC6455 peer tests exercise fragmentation, upgrade rejection, deadline/shutdown behavior, and scoped sttp use. Scripted peers exercise correlation, cancellation, duplicates, queue saturation, overflow, authentication, and batches.
- Eight live-test executions passed against Docker OBS 30.2.3 / obs-websocket 5.5.2: wrong-password rejection (4009), authenticated version/scenes discovery, Reidentify acknowledgement handling, and typed scene create/switch/event/restore/remove, each before and after a controlled OBS restart. No tests were skipped. Evidence records the official Debian 13 image digest, source fingerprint, no host mounts, loopback-only binding, and successful container removal. No recording/streaming operations were issued.
- Independent review fixed duplicate-ID cleanup, loss accounting, optional event payloads, required event intents, large-number rounding, and late-acquisition cleanup.
- The feature expansion agreed contracts before implementation and cross-reviewed protocol, core, and transport. Review fixed nested hotkey modifier encoding, duplicate nested error paths, tiny-volume conversion underflow, terminal diagnostic state/loss reporting, and clock callbacks inside the actor. Known authentication vectors caught and corrected double base64 encoding during integration. Tests exercise blocked foreign writes that only physical abort can release, alongside cancellation, bounded sampling, readiness deadlines, and raw response retention.
- The original CLI review finding is resolved: failed operations exit with status 1, verified by an actual Java subprocess test. The reusable `run` and `discover` methods continue returning `Either`.
- Final integration verifies nonfatal transport cleanup without suppressing interruption/fatal errors, consistent clean event-flow completion, and subscription-owned loss counters that remain readable after termination without accumulating session-wide subscriber history. Local invalid requests are distinguished from incoming malformed payloads.
- A six-reviewer hardening pass then fixed a server-lifecycle leak (the `server.run` entrypoint now stops Netty and releases its port on cancellation, with tests that prove rebinding), consolidated the error taxonomy to 16 cases (client-side deterministic faults such as oversized payloads are non-retryable and fail only the offending request; invariant violations are `InternalError`, not `InvalidConfiguration`), made the capability gate and `RawRequest` escape hatch coherent across single and batch APIs, unified duplicate raw-event/subscription types, and hardened the generator (override-key validation, duplicate detection, literal escaping, grammar-based enum classification, per-class Scaladoc with upstream links and schema checksum).
- This parallel review followed by sequential fixes corrected Reidentify acknowledgement handling, rejected unsafe parallel batch correlation, bounded physical socket cleanup, validated explicit ports, preserved submillisecond deadlines, redacted URI credentials, isolated the server lifecycle test, bound coverage XML to instrumentation, preserved generated field semantics, rendered Markdown tables/emphasis, and removed Python import-time execution. The public `Parallel` enum remains available but nonempty batches using it fail locally.
- The real `server.run` entrypoint starts on Java 25, serves health/Swagger/OpenAPI, and releases its port on shutdown. Endpoint tests also exercise the live server.
- Nine binary/source/Scaladoc JARs package locally; an isolated Maven consumer resolves their transitive dependencies and runs on Java 25.
- Dependency reports identify license declarations for the library graph and the separate server graph; these are inventories, not a legal compatibility opinion.
- All 18 README/guide Scala snippets compile; 17 guide pages plus three generated API references build with local links/assets checked under `/obs-websocket-client/`. Five architecture diagrams have source-hashed SVG assets. Headless Chromium checks verified diagram loading, desktop/mobile layouts, companion-source embedding, theme/search interaction, no page errors, and no mobile page overflow.
- Workflow YAML passes actionlint 1.7.12. The manual/scheduled real-OBS workflow uses the isolated Docker launcher; release compatibility approval is bound to the candidate commit. Scala production and test formatting is checked through Mill.

| Module | Executed statements | Executed branches |
| --- | ---: | ---: |
| codegen | 570 / 570 | 80 / 80 |
| protocol | 9,068 / 9,068 | 417 / 417 |
| core | 1,216 / 1,216 | 240 / 240 |
| sttp | 356 / 356 | 61 / 61 |
| examples | 34 / 34 | 4 / 4 |
| server | 106 / 106 | 12 / 12 |
| Aggregate | 11,350 / 11,350 | 814 / 814 |

Reports are generated under `out/<module>/scoverage/{xmlReport,htmlReport}.dest/`.
The coverage manifest binds source inputs and instrumenter data hashes to the run; each XML statement inventory must also match its module’s instrumenter data.
Scoverage measures instrumentable Scala source statements/branches, not dependency
internals or compiler-generated bytecode. Mill/Python/shell build tooling is verified
separately and is not included in the Scala production denominator.

Reproduce the checks with `tools/coverage.sh`, `python3 tools/check_generation.py`,
`tools/consumer_smoke.sh`, `./mill --no-server integration.test.compile`, and
`./mill --no-server site.build`. Run `tools/real_obs_smoke.sh` for disposable live OBS checks. CI workflows repeat these checks and upload evidence;
current remote results are linked from the repository’s [Actions page](https://github.com/worxbend/obs-websocket-client/actions).

## Remaining gates and limitations

- **Real OBS scope:** the successful live test covers OBS 30.2.3 / obs-websocket 5.5.2 and the operations listed above. Restart smoke opens fresh scoped connections; reconnect-loop behavior and in-flight disconnect races have deterministic test evidence, not live-restart evidence. Broader versions and catalog operations are unverified. An earlier attempt to launch installed OBS Flatpak 32.2.2 failed isolation and was stopped; the incident and possible configuration side effects are recorded in [OBS-INTEGRATION-NOTE.md](OBS-INTEGRATION-NOTE.md).
- **Schema compatibility:** the pinned development schema includes newer canvas/UUID fields. Older 5.x servers may omit required fields. No broad 5.x compatibility claim is made.
- **Publication:** first-party MIT was selected by the maintainer. Maven namespace ownership, final release version, schema-derived output licensing review, and signing/account setup remain required. The release workflow checks maintainer acknowledgements before publication.
- **GitHub:** source is pushed to `main` on the verified public `worxbend/obs-websocket-client` remote. The initial remote validation job passed, while Windows checkout changed the schema checksum and Pages deployment failed because Pages was disabled. LF checkout rules and workflow-based Pages configuration address those causes; subsequent results are recorded in Actions. Branch protection and release publication remain unverified.
- **Documentation UI:** desktop/mobile browser checks passed for the development site. The version selector currently contains development documentation only; released-version retention awaits an actual release.
- **New feature compatibility:** nested old/modern/future payload fixtures and deterministic peers validate the additions; no new live OBS run establishes cross-version compatibility for every helper or catalog request. TLS/proxy tests verify owned-client configuration, not every corporate proxy or certificate setup. Measured performance evidence exists only for two internal changes: JMH microbenchmarks (development-only `bench` module, Temurin 25.0.3) measured allocation-free UTF-8 byte counting at 7–40× the throughput of the replaced encoder-plus-scratch path with zero allocation, and measured the `JsonValue` decode at throughput parity with the legacy codec after a benchmark-caught regression in the first builder rewrite was reworked. Methodology, environment, and limitations are in [bench/README.md](bench/README.md); these microbenchmarks do not translate into user-facing throughput claims.
- **Backend ownership:** injected backends must enforce finite upgrade deadlines. Acquisition is shielded to avoid the sttp/JDK late-upgrade cancellation leak, so cancellation may wait for that configured backend deadline. The default backend is bounded by client configuration and force-closes its owned JDK client. The unreleased `withBackend` API now requires an idempotent, prompt `abortConnection` callback for individual socket cleanup; it never closes a caller-owned shared backend.
- **Tooling warnings:** Scala production compiles with warnings as errors. Mill/dependency runtime deprecation warnings and ScalaDoc's upstream classpath-option warning remain visible; Tapir can log interruption warnings during successful shutdown. Mill daemon worker reuse was unstable during concurrent development, so validation scripts use `--no-server`.

The complete release milestone in PLAN.md is not marked achieved while these gates
remain open. Locally passing tests and coverage do not imply publication or broader OBS compatibility.
