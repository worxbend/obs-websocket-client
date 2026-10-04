# Implementation and verification

Updated 2026-10-04. This report distinguishes implemented behavior, local evidence,
and remaining release gates. No external library release or Pages deployment occurred.

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
generations, refreshes credentials, reports gaps, and never transfers pending requests.

The sample exposes health, read-only OBS version discovery, Swagger assets and OpenAPI.
Typed PureConfig/HOCON settings support environment overrides and redacted secrets.

## Executed local evidence

- 2,328 Scala tests pass: codegen 14, protocol 2,188, core 67, sttp 35, examples 5, server 19.
- Fresh Scoverage measurements: 8,852/8,852 statements and 477/477 branches, with no production exclusions.
- Seven coverage-verifier tests prove rejection of missed branches, missing modules, stale sources/reports, changed instrumentation, and stray instrumented modules.
- The upstream schema checksum verifies; two offline generations and the Mill output match byte-for-byte across 217 files (216 Scala files plus inventory).
- All 26 upstream skill files still match their recorded Git blob hashes.
- Real RFC6455 peer tests exercise fragmentation, upgrade rejection, deadline/shutdown behavior, and scoped sttp use. Scripted peers exercise correlation, cancellation, duplicates, queue saturation, overflow, authentication, and batches.
- Six live-test executions passed against Docker OBS 30.2.3 / obs-websocket 5.5.2: wrong-password rejection (4009), authenticated version/scenes discovery, and typed scene create/switch/event/restore/remove, each before and after a controlled OBS restart. No tests were skipped. Evidence records the official Debian 13 image digest, source fingerprint, no host mounts, loopback-only binding, and successful container removal. No recording/streaming operations were issued.
- Independent review fixed duplicate-ID cleanup, loss accounting, optional event payloads, required event intents, large-number rounding, and late-acquisition cleanup.
- A six-reviewer hardening pass then fixed a server-lifecycle leak (the `server.run` entrypoint now stops Netty and releases its port on cancellation, with tests that prove rebinding), consolidated the error taxonomy to 15 cases (client-side deterministic faults such as oversized payloads are non-retryable and fail only the offending request; invariant violations are `InternalError`, not `InvalidConfiguration`), made the capability gate and `RawRequest` escape hatch coherent across single and batch APIs, unified duplicate raw-event/subscription types, and hardened the generator (override-key validation, duplicate detection, literal escaping, grammar-based enum classification, per-class Scaladoc with upstream links and schema checksum).
- The real `server.run` entrypoint starts on Java 25, serves health/Swagger/OpenAPI, and releases its port on shutdown. Endpoint tests also exercise the live server.
- Nine binary/source/Scaladoc JARs package locally; an isolated Maven consumer resolves their transitive dependencies and runs on Java 25.
- Dependency reports identify license declarations for the library graph and the separate server graph; these are inventories, not a legal compatibility opinion.
- README and guide Scala snippets compile; nine guide pages plus three generated API references build with local links/assets checked under `/obs-websocket-client/`.
- Workflow YAML passes actionlint 1.7.12. The manual/scheduled real-OBS workflow uses the isolated Docker launcher; release compatibility approval is bound to the candidate commit. Scala production and test formatting is checked through Mill.

| Module | Executed statements | Executed branches |
| --- | ---: | ---: |
| codegen | 408 / 408 | 69 / 69 |
| protocol | 7,423 / 7,423 | 225 / 225 |
| core | 703 / 703 | 138 / 138 |
| sttp | 231 / 231 | 39 / 39 |
| examples | 23 / 23 | 2 / 2 |
| server | 64 / 64 | 4 / 4 |
| Aggregate | 8,852 / 8,852 | 477 / 477 |

Reports are generated under `out/<module>/scoverage/{xmlReport,htmlReport}.dest/`.
The coverage manifest binds source inputs and instrumenter data hashes to the run.
Scoverage measures instrumentable Scala source statements/branches, not dependency
internals or compiler-generated bytecode. Mill/Python/shell build tooling is verified
separately and is not included in the Scala production denominator.

Reproduce the checks with `tools/coverage.sh`, `python3 tools/check_generation.py`,
`tools/consumer_smoke.sh`, `./mill --no-server integration.test.compile`, and
`./mill --no-server site.build`. Run `tools/real_obs_smoke.sh` for disposable live OBS checks. CI workflows repeat these checks and upload evidence;
no GitHub CI run has yet been observed for this implementation.

## Remaining gates and limitations

- **Real OBS scope:** the successful live test covers OBS 30.2.3 / obs-websocket 5.5.2 and the operations listed above. Restart smoke opens fresh scoped connections; reconnect-loop behavior and in-flight disconnect races have deterministic test evidence, not live-restart evidence. Broader versions and catalog operations are unverified. An earlier attempt to launch installed OBS Flatpak 32.2.2 failed isolation and was stopped; the incident and possible configuration side effects are recorded in [OBS-INTEGRATION-NOTE.md](OBS-INTEGRATION-NOTE.md).
- **Schema compatibility:** the pinned development schema includes newer canvas/UUID fields. Older 5.x servers may omit required fields. No broad 5.x compatibility claim is made.
- **Publication:** first-party MIT was selected by the maintainer. Maven namespace ownership, final release version, schema-derived output licensing review, and signing/account setup remain required. The release workflow checks maintainer acknowledgements before publication.
- **GitHub:** source is pushed to `main` on the verified public `worxbend/obs-websocket-client` remote. Workflows, branch protection, Pages, and release publication have not yet been exercised remotely.
- **Documentation UI:** the site and all local links build, but no browser surface was available for visual/mobile interaction testing. The version selector currently contains development documentation only; released-version retention awaits an actual release.
- **Backend ownership:** injected backends must enforce finite upgrade deadlines. Acquisition is shielded to avoid the sttp/JDK late-upgrade cancellation leak, so cancellation may wait for that configured backend deadline. The default backend is bounded by client configuration.
- **Tooling warnings:** Scala production compiles with warnings as errors. Mill/dependency runtime deprecation warnings and ScalaDoc's upstream classpath-option warning remain visible; Tapir can log interruption warnings during successful shutdown. Mill daemon worker reuse was unstable during concurrent development, so validation scripts use `--no-server`.

The complete release milestone in PLAN.md is not marked achieved while these gates
remain open. Locally passing tests and coverage do not imply publication or broader OBS compatibility.
