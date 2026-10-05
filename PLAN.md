# OBS WebSocket Scala client — implementation plan

Status: locally implemented and verified; release gates remain open. See [IMPLEMENTATION.md](IMPLEMENTATION.md) for measured evidence and limitations.
Updated: 2026-10-04.

## 1. Agreed requirements

- Build a Scala client for OBS WebSockets, inspired by the ergonomics and separation of concerns in [ktobs](https://github.com/Rejeq/ktobs).
- Use **Mill** as the sole project build tool. This incorporates the user's correction to the earlier Scala CLI starter instructions.
- Use the latest stable Scala 3 only. The [official release listing](https://www.scala-lang.org/download/all.html) currently identifies **3.9.0**; pin that version when bootstrapping and recheck if implementation starts later.
- Compile, test, and run on **Java 25**, using virtual threads and **Ox** structured concurrency. Do not introduce Cats Effect, ZIO, or a public effect-polymorphic API into `protocol`, `core`, or the `sttp`/`okhttp` modules; effect runtimes are confined to the opt-in `zio`/`fs2`/`pekko` adapter artifacts per [ADR-005](docs/decisions/005-backend-modules.md).
- Use **sttp** for the WebSocket client and **jsoniter-scala** for JSON.
- Use the corrected base package **`com.worxbend.obs.websocket.client`**. Use this as the provisional generator group ID; verify publishing namespace ownership before any release.
- Provide a small Tapir application using **OxStack**, the **Netty synchronous server**, Swagger UI enabled, and metrics disabled. Keep it separate from the reusable client library.
- Install and load VirtusLab's direct-style Scala skill before writing Scala. Create a complementary Mill skill.
- Evaluate the VSS tools individually; add optional integrations only when they solve an actual project use case.
- Deliver a **library-first OSS project** with a polished README, original logo/icons, comprehensive guides and API documentation, and a microsite published through **GitHub Pages**.
- Enforce **100% statement and branch coverage** for instrumentable project production code, with transparent coverage reports and required CI gates.
- Provide complete **CI/CD** for validation, documentation deployment, and versioned library releases.
- Create the public GitHub repository in the **`worxbend` organization** using `gh api`, as explicitly requested.

Working project name: `obs-websocket-client`, inferred from the requested product and used for the authorized repository-creation attempt. The existing directory is named `obs-websocker-client`; do not rename it implicitly.

## 2. Bootstrap history and current state

The paragraphs below record the original planning environment. Network and local Git access subsequently recovered; the implementation now builds and tests locally. The authoritative current evidence is [IMPLEMENTATION.md](IMPLEMENTATION.md). These historical failures must not be read as current blockers.

The visible workspace has no application source or build definition. Its `.git`, `.agents`, and `.codex` directories are read-only, and `git status` reports that this is not a Git repository. Do not claim that Git initialization has succeeded.

Java 25 is installed locally at `/home/worxbend/.sdkman/candidates/java/25.0.4-tem`; the default `java` is Java 21. Select Java 25 explicitly for the project. There is no `mill` executable on PATH, although some Mill artifacts exist in the local dependency cache.

Shell requests to GitHub, VSS, and Adopt Tapir failed because hostnames could not be resolved. The skill installer's download attempt also failed. Web browsing could read selected official documentation and the direct-style skill entrypoint, but this is not a completed local skill installation. The VSS `llms.txt` URL could not be retrieved.

Project generation, dependency resolution, compilation, startup, and the Swagger UI check remain outstanding. These are environmental limitations, not successful verification results.

The connected GitHub tool subsequently retrieved the complete upstream skill: all 26 files now exist under `skills/direct-style-scala/`, pinned to revision `51eb40a55fa1c2a4c99f2980830d28bbea4a2ac7` and verified against Git blob hashes. The entrypoint has been read locally. The custom `skills/mill-scala/SKILL.md` passes the skill validator. Root `AGENTS.md` references both for project use. Global Codex marketplace installation remains unavailable because its configuration is read-only.

The requested `gh api --method POST orgs/worxbend/repos` call was attempted with `private=false` and failed to connect to `api.github.com`. Organization/repository lookups failed too, so organization existence, permissions, and remote creation are not verified. `gh auth status` also reported an invalid credential for the configured `w0rxbend` account; recheck authentication after network access is restored. Keep the requested organization spelling `worxbend`; do not silently create under the authenticated user's different login.

## 3. Product boundary and first release

The product is a reusable JVM library. Applications should be able to connect to OBS, authenticate, issue typed requests, receive typed events, and close reliably without implementing protocol plumbing.

The first usable milestone supports connection/authentication, version discovery, scene listing and switching, and scene-change events. A later 0.1 release adds the broader request catalog, batching, robust lifecycle handling, and published usage documentation.

Target OBS WebSocket **5.x**, JSON text messages, and initially RPC version 1. Establish an explicit supported OBS version matrix during integration testing. OBS 4.x, MessagePack, Scala 2, Scala.js, Scala Native, an OBS plugin, and a desktop UI are outside the first release.

ktobs is an API-design reference, particularly its split between reusable session/API code and a transport implementation. Generate protocol bindings from the OBS specification rather than translating Kotlin implementation code. Respect upstream licensing if any code or documentation is copied.

## 4. VSS adoption decisions

The [VSS site](https://vss.virtuslab.com/) and [machine-readable stack description](https://vss.virtuslab.com/llms.txt) are the requested stack references. The latter must be read successfully during bootstrap. The decisions below are project choices, not claims that every VSS component is required.

| Tool | Decision | Intended use |
| --- | --- | --- |
| Scala 3 | Required | Typed protocol models, error ADTs, opaque identifiers, direct-style public API. |
| Mill | Required | Build, tests, code generation, formatting, documentation, packaging, publishing. |
| Java 25 | Required | Execution environment for virtual threads and the full test suite. |
| Ox | Required | Connection scopes, reader/writer workers, channels, cancellation, deadlines. |
| sttp | Required | Synchronous WebSocket transport; assess its Ox adapter in the transport spike. |
| jsoniter-scala | Required | Wire codecs and Tapir request/response JSON. |
| Ox flows | Required for event consumption | Scoped, bounded event pipelines; examples for filtering and aggregation. |
| Tapir + Netty sync | Required sample application | Local HTTP demonstration and Swagger UI at `/docs`. No dependency from the client core. |
| sttp-ai | Optional later example | A constrained assistant that calls a small allowlist of OBS operations. |
| Orca | Optional later experiment | A multi-step AI-assisted production workflow if a concrete use case emerges. |
| Parlance | Deferred | Persistence only if an application needs event history, settings, or audit records. |
| Besom | Deferred | Infrastructure for a separately deployed service; a local OBS client needs no cloud provisioning. |

Keep AI credentials, cloud providers, and database dependencies outside the default library dependency graph. Installation of a skill never makes its associated technology a project requirement.

## 5. Skills and repository conventions

### Upstream Scala skills

Inventory all `SKILL.md` entries in the requested [VirtusLab/scala-skill repository](https://github.com/VirtusLab/scala-skill) at installation time. Its README currently advertises one skill, `direct-style-scala`, with multiple supporting chapters. Install the complete skill directory, including those chapters; do not confuse chapters with separate skills.

Follow the README's Codex marketplace workflow when writable:

```sh
codex plugin marketplace add virtuslab/scala-skill
```

Then install `direct-style-scala` through Codex's plugin UI. Check for an existing installation before replacing anything. Record the upstream revision for reproducibility. If more Scala skills are present in the repository, include them after inspecting their descriptions. The session already exposes ZIO-specific skills; their availability does not authorize switching this project to ZIO.

Read the direct-style entrypoint and the relevant setup, organization, resource-management, concurrency, external-stream, JSON, and HTTP chapters before their corresponding implementation work.

### Project Mill skill

Create `skills/mill-scala/SKILL.md` as the versioned source, then install it in a discoverable skill directory when filesystem access permits. It should explain:

- Mill is authoritative; adapt upstream sbt/Scala CLI examples to the actual Mill release.
- How to inspect the pinned wrapper, task graph, module layout, dependency declarations, and JVM selection.
- How to select Java 25 for both the build process and application/test forks.
- How to compile, test, format, generate sources, and run the Tapir sample through Mill.
- How to keep upstream direct-style conventions while avoiding unrelated technology choices.
- How to report unexecuted checks accurately when downloads or tools are unavailable.

Validate the skill's metadata and links. Keep third-party skill sources separate from project-specific conventions and do not silently edit the upstream skill.

## 6. Bootstrap and Adopt Tapir migration

1. Confirm the project name; retain the corrected package.
2. Complete upstream skill installation and read the required chapters.
3. Inspect Adopt Tapir's current builder options. Do not assume that a `Mill` enum exists.
4. If Mill is supported, request it directly. Otherwise generate a temporary starter with a supported builder, retaining `OxStack`, `Netty`, documentation on, metrics off, `Jsoniter`, and `Scala3`, then migrate the generated sources and dependencies into Mill. A temporary generator format is not a second maintained build.
5. Download into a temporary directory. Inspect archive paths and files before copying anything into the workspace. Record generator settings and generation date.
6. Pin a stable Mill release, its official Unix/Windows launchers, Scala 3.9.0, and a Java 25 distribution. Mill documentation observed during planning identifies 1.1.9; verify the actual stable release before choosing the pin.
7. Translate all generated source directives, dependencies, resources, main-class settings, and test settings into `build.mill`. Use the current Mill API rather than mixing legacy `ivyDeps` examples with modern APIs.
8. Configure Java 25 in Mill. A Scala CLI `//> using jvm 25` directive is not a substitute for Mill JVM configuration.
9. Add `.gitignore`, `.editorconfig`, Scalafmt configuration, README, and local environment-variable documentation. Choose a license with the owner before publishing.
10. Compile and run the sample using Mill. Check `/docs`, its Swagger assets, and the referenced OpenAPI document. Stop the application and verify clean shutdown.

Read [Mill's Scala build guide](https://mill-build.org/mill/scalalib/intro.html) and [installation guide](https://mill-build.org/mill/cli/installation-ide.html) for the selected version. Use `ScalaModule` with the native `<module>/src` and `<module>/test/src` layout. Configure dependencies centrally and keep all project modules on the same Scala/JVM versions.

## 7. Intended repository layout

This is the target layout, not a list of files already created. Add modules as their implementation phases begin.

```text
build.mill
.mill-version
mill
mill.bat
.scalafmt.conf
README.md
PLAN.md
skills/mill-scala/SKILL.md
protocol-spec/
  protocol.json
  provenance.json
codegen/
  src/
  test/src/
protocol/
  src/com/worxbend/obs/websocket/client/protocol/
  test/src/
core/
  src/com/worxbend/obs/websocket/client/
  test/src/
sttp/
  src/com/worxbend/obs/websocket/client/transport/sttp/
  test/src/
okhttp/
  src/com/worxbend/obs/websocket/client/transport/okhttp/
  test/src/
zio/
  src/com/worxbend/obs/websocket/client/transport/zio/
  test/src/
fs2/
  src/com/worxbend/obs/websocket/client/transport/fs2/
  test/src/
pekko/
  src/com/worxbend/obs/websocket/client/transport/pekko/
  test/src/
examples/
  src/com/worxbend/obs/websocket/client/examples/
server/
  src/com/worxbend/obs/websocket/client/server/
  resources/
  test/src/
integration/
  test/src/
docs/
.github/workflows/
```

Dependency direction: `protocol <- core <- {sttp, zio, fs2, pekko}` and `sttp <- okhttp`; `server` and `examples` consume `sttp`; integration tests exercise the assembled client. `codegen` reads the pinned specification and produces protocol source inputs. Protocol models must not depend on HTTP server or AI packages. Each `transport.*` module publishes its own self-contained artifact per [ADR-005](docs/decisions/005-backend-modules.md).

Avoid package-shadowing issues between the project's `transport.sttp`/`transport.zio`/`transport.fs2` and upstream imports; use explicit root imports where necessary.

## 8. Public API design

Offer a scoped client entrypoint: the caller supplies configuration and a function that uses a connected session. The function's lifetime owns the session. Do not return a live session whose worker lifetime has already ended.

Design goals:

- A request's result type is determined by its request type; callers should not cast JSON.
- Use named convenience operations for common workflows and a generic typed request operation for the complete catalog.
- Return `Either[ObsError, A]` for expected failures; distinguish transport failure, authentication failure, incompatible protocol, request rejection, timeout, malformed payload, overflow, and closed session.
- Include request type, request ID, and OBS status details in rejection errors without exposing credentials.
- Expose immutable connection metadata and supported request names after identification/version discovery.
- Provide scoped event subscriptions with filtering by typed event category.
- Provide a clearly named raw request/event escape hatch for vendor extensions and newer protocol fields.
- Use domain wrappers for request IDs, scene/input identifiers, subscription masks, limits, and validated configuration where they prevent mistakes.

Decide the exact signatures in a small API spike. Compile representative examples before committing to a large generated API. Keep transport types and implementation actors out of public signatures.

## 9. Protocol modeling and generation

Use the official [OBS protocol schema](https://raw.githubusercontent.com/obsproject/obs-websocket/master/docs/generated/protocol.json) as the catalog source. Pin a reviewed upstream commit and checksum in `protocol-spec/provenance.json`; normal builds must not fetch a moving branch.

Create a small normalized intermediate representation before emitting Scala. Account for optional and nullable values, empty payloads, arrays, documented numeric constraints, version annotations, enums, and arbitrary settings objects. Maintain explicit reviewed overrides where the schema is insufficient; never guess that every documented number is an `Int`.

Generate request/response models, event models, codecs, and a coverage inventory. Keep envelope decoding and handwritten session logic separate. Preserve unknown fields where useful, unknown enum/status values as data, and unknown events through a bounded raw representation. Reject malformed known payloads with actionable errors.

The generator must be deterministic: stable ordering, stable formatting, no timestamps in emitted source, and identical output for identical inputs. Prefer Mill-managed generated sources under `out/`; commit the schema, overrides, and golden fixtures. Add a regeneration check and catalog diff for protocol upgrades.

Support JSON text initially. Distinguish an omitted property from explicit `null` where wire semantics require it; do not assume a default derived codec models both correctly.

## 10. Connection state machine and authentication

Follow the official [OBS connection and message specification](https://raw.githubusercontent.com/obsproject/obs-websocket/master/docs/generated/protocol.md). Support Hello, Identify, Identified, Reidentify, Event, Request, RequestResponse, RequestBatch, and RequestBatchResponse. Send no application requests before successful identification.

Implement authentication using UTF-8 and the specified construction: `secret = Base64(SHA256(password + salt))`, then `authentication = Base64(SHA256(secret + challenge))`. Test published examples and non-ASCII credentials. Negotiate supported RPC versions and preserve close codes. Treat subscription masks explicitly, with high-volume events opt-in.

Project lifecycle design: represent Connecting, AwaitingHello, Identifying, Ready, Closing, Closed, and Failed as explicit states. Make transitions independently testable. Apply separate connection, handshake, request, and shutdown deadlines. Require explicit handling of malformed messages and unexpected state transitions.

Do not log passwords, authentication strings, or complete settings payloads. Permit a password provider so reconnect can obtain updated credentials. Make configuration rendering redact secrets by construction.

## 11. sttp transport and Ox ownership

The [sttp synchronous WebSocket API](https://sttp.softwaremill.com/en/latest/other/websockets.html) supports scoped `SyncWebSocket` interaction and an Ox integration. Evaluate the documented synchronous backend and Ox channel adapter with the selected dependency versions. Test cancellation, fragmentation, and close behavior before building the session engine on top.

Project design:

- One connection scope owns the socket, reader, writer, dispatcher, and subscriber registry.
- One reader handles incoming frames continuously. Serialize outgoing frames through one bounded writer path.
- Confine pending requests and subscriber state to an Ox actor or one channel-driven state loop.
- Register a request before sending it; handle send failure by removing and completing the same pending entry.
- Correlate responses independently of arrival order. A response, timeout, cancellation, or disconnect completes each pending request at most once.
- Bound in-flight requests and outgoing queue capacity. Reject or wait with a deadline when capacity is exhausted.
- Never execute user callbacks on the socket reader or block response dispatch behind a slow event consumer.
- Verify that shutdown interrupts or closes a blocked receive before joining worker scopes. A finalizer that runs only after a blocked join can deadlock.
- Close resources in ownership order. An injected shared backend remains owned by its caller; a backend constructed internally is closed by the client.

Abstract transport just enough to substitute deterministic scripted messages in tests. Do not build a generic networking framework or introduce an effect type parameter. Alternative WebSocket backends (OkHttp, ZIO, fs2, Pekko) live in separate adapter modules that implement the same blocking `ObsTransport` seam; see §16 Phase 7 and [ADR-005](docs/decisions/005-backend-modules.md).

## 12. Events, overflow, and reconnect

Provide broadcast subscriptions rather than making multiple subscribers compete for one queue. Document that a new subscriber receives future events, not historical replay. Deliver each subscriber's events in receive order until an explicitly reported overflow.

Use bounded buffers. Default overflow behavior should fail the affected subscription with a typed error, leaving response dispatch healthy. Offer explicitly selected drop/coalescing policies for high-frequency meter data and expose loss counts. Do not silently drop control events or retain unbounded event history.

Reconnect is optional and disabled initially. Later implement bounded exponential backoff with jitter and a fresh connection generation ID. Fail outstanding requests from the old generation; never automatically replay mutating requests. Authentication and unsupported-protocol failures should stop retries. Restore desired subscriptions after successful reidentification and emit an explicit gap/reconnected notification.

Also stop automatic reconnect on `SessionInvalidated` (4011), as required by the [OBS close-code specification](https://github.com/obsproject/obs-websocket/blob/master/docs/generated/protocol.md#websocketclosecodesessioninvalidated).

Distinguish a local timeout from proof that OBS did not execute a request. Document this ambiguity for mutating operations and batches.

## 13. Request coverage and batching

Implement a narrow vertical slice first: GetVersion, GetSceneList, GetCurrentProgramScene, SetCurrentProgramScene, and relevant scene events. Then expand by workflow: inputs/audio, scene items, transitions/studio mode, outputs/recording/streaming, filters/media, configuration, and vendor extensions.

Track every upstream request and event as generated, tested, or intentionally deferred. Do not equate compiling a generated class with validated protocol behavior.

For batches, preserve individual request result types/statuses, ordering, execution mode, and halt behavior. Clearly document that batching is not a database transaction and supplies no rollback. Treat a partially observed batch outcome as ambiguous instead of retrying it automatically.

## 14. Tapir sample and adding an endpoint

The sample demonstrates using the client from direct-style HTTP handlers. Start with a standalone health endpoint so startup and Swagger checks work without a running OBS instance. Add read-only OBS status/scenes endpoints once the client vertical slice is ready. Bind locally by default; expose OBS-changing operations only deliberately.

Endpoint workflow:

1. Define the input/output DTOs in the server package and derive jsoniter codecs and Tapir schemas where needed.
2. Describe the method, path, inputs, success output, and typed error output with Tapir.
3. Attach direct-style logic using `.handle` or `.handleSuccess` and call a small service function.
4. Add the endpoint to the common collection used by Netty sync and Swagger/OpenAPI generation.
5. Map client failures to documented HTTP responses; avoid returning raw exceptions or authentication details.
6. Add a focused endpoint test and check its OpenAPI representation.

Keep connection ownership at application scope when sharing a session across requests. Per-request cancellation must cancel that request's wait, not tear down unrelated callers' connection. Verify this explicitly before adopting the shared-session design.

## 15. Verification strategy

| Layer | Required evidence |
| --- | --- |
| Build/toolchain | Mill resolves modules; compile/test/run select Java 25; all project modules use the pinned Scala 3 version. |
| JSON/protocol | Golden envelopes, optional/null distinctions, unknown variants, malformed payloads, numeric limits. |
| Authentication | Known vectors, no-auth server, missing/wrong password, Unicode input, redacted diagnostics. |
| State machine | No pre-identification requests; invalid transitions; all terminal paths complete pending work. |
| Request dispatch | Out-of-order responses, concurrent calls, late/duplicate responses, timeout/send/disconnect races. |
| Resource handling | Blocked receives terminate; repeated connect/close cycles leave no workers or sockets behind. |
| Event delivery | Two subscribers receive broadcasts; slow subscriber cannot starve responses; overflow is observable. |
| Transport | Local WebSocket peer verifies frame fragmentation, ping/pong, close, failed upgrade, limits, TLS failures. |
| Batches | Mixed success/failure, halt behavior, result correlation, ambiguous disconnect. |
| HTTP sample | Health response, `/docs` HTML, Swagger assets, valid OpenAPI document, clean shutdown. |
| Real OBS | Authenticated connection, scene discovery/change/event, reconnect, and supported version matrix. |

Inject clocks, request-ID generation, and retry randomness. Coordinate concurrency tests with channels/barriers rather than arbitrary sleeps. Every potentially blocking test needs a deadline and cleanup. Use generated round trips alongside independent golden fixtures so encoder and decoder bugs cannot merely agree with each other.

Run local/fake-peer tests in ordinary CI without OBS or external credentials. Run real OBS tests separately against a disposable scene collection; do not start a user's live stream or recording as an automatic smoke test. Document the OS/display setup needed for OBS integration runs.

## 16. Delivery phases and acceptance gates

### Phase 0 — Skills and build bootstrap

- [x] Confirm project name and install all skills supplied by the requested VirtusLab repository.
- [x] Author and validate the Mill skill; reference it from project `AGENTS.md`.
- [x] Generate the Adopt Tapir starter and migrate to Mill if necessary.
- [x] Pin toolchain/dependencies and add source formatting and README instructions.
- [x] Compile and run on Java 25; verify Swagger UI and its OpenAPI document.

Gate: a fresh checkout builds through Mill and the documented local server command works.

### Phase 1 — Transport and protocol foundation

- [x] Pin the OBS schema and define the module boundaries.
- [x] Spike sttp/Ox socket ownership and cancellation.
- [x] Implement envelope codecs, typed failures, configuration, authentication, and lifecycle states.
- [x] Build a scripted peer and focused protocol tests.

Gate: connect, authenticate, reject incompatible peers cleanly, and shut down without leaked workers.

### Phase 2 — First useful client

- [x] Implement request correlation, timeouts, and bounded concurrency.
- [x] Deliver scene/version operations and broadcast scene events.
- [x] Add a minimal client example and read-only HTTP sample integration.
- [x] Verify against a disposable real OBS setup.

Gate: an application can list/switch scenes and observe changes using a scoped typed API.

### Phase 3 — Catalog generation and API coverage

- [x] Implement deterministic schema normalization and generation.
- [x] Add reviewed overrides, generated codec fixtures, and catalog coverage reports.
- [x] Expand request/event categories and implement batch execution.
- [x] Provide raw extension handling without compromising typed operations.

Gate: all supported schema entries have an explicit coverage status and reproducible generated sources.

### Phase 4 — Reliability and integration

- [x] Exercise races, queue saturation, slow consumers, and frame limits.
- [x] Add opt-in reconnect with generation isolation and event-gap reporting.
- [x] Validate shutdown under blocked I/O and backend ownership rules.
- [x] Finish HTTP error mapping and documentation.

Gate: the documented failure behavior holds under deterministic fault tests and real OBS restart tests.

### Phase 5 — Release readiness

Local Scala coverage is measured at 100% statements/branches; the authored CI workflow has not yet run remotely. MIT is selected. Unchecked combined items below retain their external/deployment/real-OBS gates.

- [x] Build CI, API docs, source/docs artifacts, and dependency/license reports.
- [ ] Reach and enforce 100% statement/branch coverage, with reports available as CI artifacts.
- [ ] Finish the branded README, contributor documentation, and versioned GitHub Pages microsite.
- [x] Record tested Scala/JDK/OBS versions, tested operation scope, and API compatibility policy.
- [ ] Confirm license, Maven namespace ownership, release version, and repository metadata.
- [x] Test publication locally before configuring signed release publishing.
- [ ] Publish only as an explicitly requested release action.

Gate: a consumer example builds against the packaged artifacts, coverage gates pass, the public microsite works at its repository base path, and release instructions are repeatable.

### Phase 6 — Optional VSS examples

Evaluate sttp-ai, Orca, Parlance, and Besom independently after the client is stable. Each addition needs a concrete example, isolated dependencies, documented setup, and a test strategy. None should delay the core client release.

### Phase 7 — Backend modules

- [ ] Move backend-agnostic reconnect/handshake machinery from `transport.sttp` into `core`.
- [ ] Add the `okhttp` sync adapter artifact (`obs-websocket-client-okhttp`).
- [ ] Add the `zio`, `fs2`, and `pekko` bridged async adapter artifacts.
- [ ] Wire every new module into coverage, Scalafix, release, consumer-smoke, API-site, and CI enumerations.
- [ ] Document backend selection and coordinates in README, quickstart, and guides.

Gate: each backend artifact compiles, passes its own 100% statement/branch coverage gate, and builds as a standalone consumer dependency per [ADR-005](docs/decisions/005-backend-modules.md).

## 17. CI and developer commands

Planned module commands, to become executable after bootstrap:

```sh
./mill server.compile
./mill server.test
./mill server.run
./mill protocol.test
./mill core.test
./mill sttp.test
```

Resolve the actual formatting, code-generation, documentation, and integration tasks from the completed build rather than documenting guessed task names. CI should compile all implemented modules, run focused tests, enforce formatting, and compare generated outputs. Pin CI actions and tool versions. Keep publication credentials out of pull-request jobs.

Use compiler warnings for unused declarations and discarded/non-Unit statements once the starter is migrated, with deliberate handling of generated code. Apply warning-as-error enforcement after eliminating existing warnings. Avoid enabling unrelated experimental language features.

## 18. Risks and decisions to resolve

| Risk or uncertainty | Resolution |
| --- | --- |
| Mill generation may not be supported by Adopt Tapir | Inspect generator options; migrate a temporary supported starter while preserving requested stack settings. |
| Latest Scala exposes dependency/macro compatibility issues | Compile the minimal dependency set early; update dependencies instead of silently downgrading Scala. |
| Socket receive does not respond to interruption | Prove close-before-join teardown with a blocked-reader test. |
| Protocol schema omits semantic details | Maintain small reviewed overrides and independent fixtures. |
| Event throughput exceeds subscriber capacity | Bounded broadcast queues with explicit overflow policy and diagnostics. |
| Reconnect duplicates side effects | Never replay outstanding requests automatically; report uncertain outcomes. |
| Tooling defaults to Java 21 | Verify Java 25 separately for Mill, application, and test processes. |
| Skill install / Git metadata remain unwritable | Report the exact unfinished step; do not modify protected paths or pretend installation succeeded. |
| Broad VSS adoption inflates dependencies | Add optional tools only in independent examples with demonstrated value. |

Current open items: optional global marketplace registration, broader real OBS compatibility verification, Maven namespace ownership and signing setup, schema-derived output licensing review, remote CI/Pages deployment, and an explicitly requested release. Generator access, exact dependency pins, public repository identity, and first-party MIT selection are verified; see IMPLEMENTATION.md.

## 19. Completion criteria

The repository is initialized when the Mill build, Java 25 configuration, installed skills, sample server, documentation, and smoke checks are in place and verified. The OBS client is complete for the first release when typed requests/events, authentication, bounded concurrency, shutdown, failure behavior, protocol coverage, tests, and consumer documentation satisfy their phase gates.

This plan does not mark either milestone as complete merely because the plan itself exists.

## 20. OBS protocol research and reference map

Research was performed against the official OBS repository on 2026-10-04. Its `master` documents are moving references; they do not prove that every listed feature exists in a released OBS build. Before generation, record a concrete schema commit, release provenance, and checksums. Keep links to both the pinned revision and the upstream reference in generated API documentation.

The upstream [README](https://github.com/obsproject/obs-websocket#readme) states that OBS Studio 28 and later include obs-websocket, and that the default 5.x port is 4455. The setup guide should explain enabling the server and finding its authentication settings. Use [OBS releases](https://github.com/obsproject/obs-studio/releases) and [obs-websocket releases](https://github.com/obsproject/obs-websocket/releases) when choosing the integration matrix; do not infer it from protocol version alone.

| Primary reference | Implementation responsibility | Verification to add |
| --- | --- | --- |
| [Protocol overview](https://github.com/obsproject/obs-websocket/blob/master/docs/generated/protocol.md#general-intro) | Explicit 5.x/RPC compatibility boundary. | Reject incompatible handshake versions. |
| [Connection steps](https://github.com/obsproject/obs-websocket/blob/master/docs/generated/protocol.md#connection-steps) | Ordered handshake and readiness. | No commands before Ready; handshake deadline. |
| [Authentication](https://github.com/obsproject/obs-websocket/blob/master/docs/generated/protocol.md#creating-an-authentication-string) | Dedicated authentication helper. | Independent challenge/response vectors. |
| [Message opcodes](https://github.com/obsproject/obs-websocket/blob/master/docs/generated/protocol.md#message-types-opcodes) | Envelope ADT and decoder. | Known-message fixtures and invalid opcodes. |
| [Reidentify](https://github.com/obsproject/obs-websocket/blob/master/docs/generated/protocol.md#reidentify-opcode-3) | Subscription updates. | Check acknowledgement behavior against the pinned server; do not invent a correlated response. |
| [Request](https://github.com/obsproject/obs-websocket/blob/master/docs/generated/protocol.md#request-opcode-6) and [response](https://github.com/obsproject/obs-websocket/blob/master/docs/generated/protocol.md#requestresponse-opcode-7) | Typed correlation and rejection errors. | Out-of-order results and absent result payloads. |
| [Events](https://github.com/obsproject/obs-websocket/blob/master/docs/generated/protocol.md#event-opcode-5) and [subscriptions](https://github.com/obsproject/obs-websocket/blob/master/docs/generated/protocol.md#eventsubscription) | Event dispatch and bitmask model. | Broadcast, filtering, and high-volume opt-in. |
| [Batch request](https://github.com/obsproject/obs-websocket/blob/master/docs/generated/protocol.md#requestbatch-opcode-8), [response](https://github.com/obsproject/obs-websocket/blob/master/docs/generated/protocol.md#requestbatchresponse-opcode-9), and [execution modes](https://github.com/obsproject/obs-websocket/blob/master/docs/generated/protocol.md#requestbatchexecutiontype) | Explicit serial/frame/parallel modes. | Partial results, halted requests, and result association. |
| [Close codes](https://github.com/obsproject/obs-websocket/blob/master/docs/generated/protocol.md#websocketclosecode) and [request statuses](https://github.com/obsproject/obs-websocket/blob/master/docs/generated/protocol.md#requeststatus) | Separate session errors from operation errors. | Retry classification, including no reconnect after 4011. |
| [GetVersion](https://github.com/obsproject/obs-websocket/blob/master/docs/generated/protocol.md#getversion) | Discover available requests at runtime. | Gate operations by capability; preserve unknown capabilities. |
| [Machine-readable schema](https://github.com/obsproject/obs-websocket/blob/master/docs/generated/protocol.json) | Reproducible generated catalog. | Coverage inventory and schema-upgrade diff. |
| [Server implementation](https://github.com/obsproject/obs-websocket/tree/master/src) | Resolve ambiguous behavior in the matching revision. | Real-server regression fixtures for discrepancies. |

Protocol research deliverable: a reviewed compatibility note listing the pinned schema revision, tested OBS releases, unsupported items, and any specification/implementation discrepancies. Include newer schema categories in the coverage inventory even if they are unavailable in the minimum supported release.

## 21. Library packaging and OSS documentation

Publish independently consumable Scala 3 artifacts: protocol models, core session logic, and one adapter artifact per supported WebSocket backend (JDK `sttp`, OkHttp, ZIO, fs2, Pekko) per [ADR-005](docs/decisions/005-backend-modules.md). Confirm final artifact names and Maven organization before release. The HTTP sample, database integrations, AI examples, and infrastructure code are not mandatory runtime dependencies of the library.

The README is the front door: an original project mark, clear one-sentence purpose, compact truthful badges, a minimal working usage example once implemented, installation coordinates once published, links to guides/API docs, compatibility, development commands, roadmap, and contribution information. Label planned work honestly; do not show passing CI, 100% coverage, or a released version before those claims are verified.

Documentation deliverables:

- **Quickstart:** OBS setup, authenticated connection, one read operation, one event subscription, and guaranteed cleanup.
- **Installation:** supported Scala/JDK versions, real artifact coordinates, and a Mill consumer example.
- **Guides:** requests, events, subscription masks, typed failures, deadlines, batches, raw extensions, reconnect policy, and shutdown ownership.
- **API reference:** Scaladoc for public types and generated request/event models, with upstream protocol links.
- **Recipes:** scene switching, audio controls, recording status, and the optional Tapir service.
- **Operations:** connection troubleshooting, compatibility matrix, diagnostics/redaction, and local versus remote OBS configuration.
- **Contributors:** Mill commands, package boundaries, fixtures, code generation, coverage requirements, testing without OBS, release procedure, and architecture decisions.
- **Project health:** chosen license, changelog, issue/PR templates, support boundaries, and responsible security-reporting instructions once a maintainer contact is established.

Compile documentation snippets against the actual library in CI using a Mill task or a compatible documentation tool. Verify examples from published artifacts before each release. Keep the README concise and place detailed explanations on the microsite.

## 22. Microsite and GitHub Pages

Publish a static documentation microsite at the intended project URL `https://worxbend.github.io/obs-websocket-client/` after the public organization repository exists and Pages is enabled. This URL is a deployment target, not a currently verified live site.

Prefer Scala 3 Scaladoc's static-site support if it meets navigation/search/versioning requirements; otherwise select a documentation generator with a documented Mill build step. Record the choice after a small build spike. Mill remains the entrypoint for site generation and snippet compilation.

Design requirements: reuse the README's mark and palette; responsive typography; accessible contrast and focus indicators; light/dark themes; keyboard-accessible navigation; code-copy controls; searchable guides/API docs; mobile layout; version selector; edit-source links; and a clear project status. Avoid analytics or third-party services that are unnecessary for reading docs.

Information architecture: Home, Getting Started, Guides, API Reference, Recipes, Protocol Compatibility, Contributing, and Releases. Keep release documentation versioned and distinguish the development version from the latest stable release.

Build with the `/obs-websocket-client/` base path and verify all CSS, images, favicon, API links, navigation, and deep links under that prefix. Include sitemap, metadata, social preview asset, and a useful 404 page. Link-check local pages on every PR; check external links separately with retry limits.

Use GitHub's [custom Pages workflow](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages): build the static artifact in CI, upload it, then deploy using the Pages environment. Grant deployment permissions only to the deployment job. Pull requests build and validate the site without publishing production. Deploy the main documentation automatically after required checks pass; retain stable release documentation on tags.

## 23. Exact meaning of 100% coverage

The requested target is **100% executed statements and 100% executed branches**, both per production module and in aggregate, for all instrumentable first-party Scala production code. Include handwritten code, generated source, generator logic, and production code in shipped examples. Document what the selected instrumenter can and cannot measure; do not call missing instrumentation full coverage.

Use Mill's [Scoverage integration](https://mill-build.org/mill/contrib/scoverage.html), subject to an early Scala 3.9/Java 25 compatibility spike. Pin compatible versions. Aggregate unit and deterministic integration results only when the instrumentation identifiers and source revision match. Test a deliberately missed branch to prove that the CI gate fails below 100%.

Coverage implementation requirements:

- Report covered and total statements/branches with XML and HTML artifacts, plus per-module totals in the CI summary.
- Fail on absent reports, stale reports, missing expected production modules, or a non-empty module with zero measured statements. Mark a truly zero-branch module as not applicable rather than dividing by zero.
- Avoid rounded percentages for enforcement; require covered counts to equal total counts.
- Exclude only test code, dependency code, and non-code resources from the production denominator. No blanket exclusions for generated sources, error paths, or difficult concurrency code.
- Investigate compiler-generated or unreachable constructs that the tool reports incorrectly. Track a precise tooling limitation rather than silently lowering thresholds or inserting broad ignore annotations.
- Collect real OBS integration coverage separately if it cannot be deterministic in ordinary CI; required branch coverage must still be achievable with the scripted peer.
- Publish a measured coverage badge only after the pipeline produces trustworthy data. Before then label 100% as a target.

Coverage does not replace behavioral verification. Keep protocol fixtures, property tests for generators/codecs, concurrency fault tests, and consumer examples as separate correctness gates. Add mutation testing for critical state transitions if a compatible tool is available and its cost is acceptable; it is supplemental to the explicit 100% requirement.

## 24. CI/CD and repository setup

### Public GitHub repository

The user authorized creation in the `worxbend` organization. Recheck organization ownership/access and repository existence when connected. If absent, use `gh api --method POST orgs/worxbend/repos` with the chosen name, `private=false`, and `auto_init=false`. Verify the returned owner, full name, and public visibility. An existing repository should be inspected and preserved rather than overwritten.

Initialize local Git and attach the verified remote only when the metadata directory is writable. Do not put machine-specific setup reports, credentials, caches, or upstream downloads into commits accidentally. Repository creation does not itself publish a library release.

### Workflow matrix

| Workflow | Trigger | Required output |
| --- | --- | --- |
| Validate | PR and main push | Formatting, warnings, all-module compilation, unit/fake-peer tests, 100% coverage gate, generated-source checks. |
| Compatibility | PR/main as practical, scheduled broader runs | Java 25 and supported desktop OS validation; dependency/toolchain compatibility. |
| Documentation | PR and main push | Compiled snippets, API reference, static site, internal link and base-path checks. |
| Pages deploy | Successful main validation; release documentation updates | Verified Pages deployment and URL smoke test. |
| Real OBS | Manual and scheduled isolated runs | Supported OBS matrix with deterministic disposable scene fixtures and captured diagnostics. |
| Release | Maintainer-triggered semantic version tag | Revalidated source/docs/JAR artifacts, checksums, signatures/provenance as supported, Maven publication, GitHub release notes. |
| Dependency currency | Manual check before each release | Every pinned version compared against upstream Maven metadata; deliberate updates that pass the same required checks. |

Pin third-party actions by commit and tool versions explicitly. Use minimal workflow permissions, bounded execution times, concurrency cancellation for superseded PR jobs, and caches keyed by OS/toolchain/dependency inputs. Never run untrusted PR code with release credentials. Keep test output and coverage reports available on failure without leaking secrets.

Protect the default branch with required checks after the workflows exist. Use environment-scoped credentials for Maven publication and the Pages deployment job. Release automation must verify the tag/version match, consume artifacts from the tested revision, fail before publication if checks fail, and avoid overwriting an existing version. Document recovery from partial publication.

Add a release checklist covering compatibility, changelog, coordinates, license, 100% coverage reports, documentation links, consumer smoke test, and Pages health. The release pipeline is now wired end-to-end — tag-gated Sonatype publication plus a GitHub Release job — though no release or documentation deployment has occurred yet.

Status as of 2026-10-04: CI caching is delivered — `validate.yml` and `compatibility.yml` use `actions/cache` keyed by OS and dependency/toolchain inputs (`build.mill`, `.mill-version`). A successful publish now creates the GitHub Release with generated notes for the maintainer-triggered tag. Dependabot has no Mill ecosystem, so dependency currency is a manual per-release check against Maven metadata instead of scheduled update PRs; the 2026-10-04 check found every pin current except logback-classic, updated from 1.6.4 to 1.6.5.

## 25. Peer-inspired expansion agreed on 2026-10-04

A structured peer comparison of goobs, tinodo/obsclient, and the legacy Java client agreed module contracts before implementation and cross-reviewed protocol, core, and transport before central validation. The [feature comparison](docs/feature-expansion.md) and [ADR-004](docs/decisions/004-additive-peer-features.md) record the scope and trade-offs.

Add generated request-category facades, typed nested model views, typed event selectors, per-call budgets, raw-plus-typed response envelopes, bounded diagnostics and optional event sampling, read-only NotReady recovery, validated references, transport write deadlines/headers/TLS/proxy configuration, and pure workflow helpers. Preserve raw extension APIs, unknown fields, scoped ownership, bounded queues, and no replay of uncertain operations. Correct wire-format defects discovered through the peer comparison, including nested hotkey modifiers.

These changes use the same exact coverage, deterministic generation, warnings-as-errors, compiled documentation, and packaging gates as the original library. Synthetic version fixtures do not establish live compatibility, and feature parity does not establish performance superiority. Published artifacts, deployment, and live OBS results remain separately reported.
