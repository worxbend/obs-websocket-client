# Architecture review and documentation report

Reviewed 2026-10-04 against the local implementation based on commit `fd2f2d0`, with documentation changes in the working tree. Scope: module boundaries, connection ownership, request/event dispatch, reconnect, the HTTP sample, and the version/scenes companion. This is a bounded source review, not a comprehensive security audit. No Scala implementation was changed.

The implementation supports the architecture described in the [overview](architecture.md). The [how-to guide](guides/read-version-and-scenes.md) reuses the existing compiled companion. Three [decision records](decisions.md) preserve the agreed design without inventing historical decisions or release claims. A source fact-check and a confirming guide review found no remaining documentation drift.

## Review finding

examples/src/com/worxbend/obs/websocket/client/examples/Quickstart.scala:L32: 🟡 risk: `main` prints a failed connection or request and returns normally, so shell automation can treat failure as success. Return a nonzero process status for `Left` at the CLI boundary and verify it with a subprocess test.

Resolved by the subsequent peer-inspired implementation: the CLI now exits with status 1 on failure after scoped cleanup. A subprocess test verifies the actual exit status, while `run` and `discover` retain their `Either` APIs. The original documentation-only review did not change Scala code; its historical evidence below is retained. Current measurements are in [IMPLEMENTATION.md](../IMPLEMENTATION.md).

## Evidence, finding, and path

| Evidence | Finding | Path through the system |
| --- | --- | --- |
| [build.mill](../build.mill) | The server and examples consume the reusable library; generation is build-time work | Applications → sttp → core → protocol; schema → codegen → generated sources |
| [ObsClient.scala](../core/src/com/worxbend/obs/websocket/client/ObsClient.scala) | Application use follows identification and capability discovery | Upgrade → actor/read/write workers → handshake → raw GetVersion → callback |
| [SessionLogic.scala](../core/src/com/worxbend/obs/websocket/client/SessionLogic.scala) | Pending requests and independent event subscriptions are actor-owned | Request registration → writer → reader → correlation or broadcast |
| [ReconnectingObsClient.scala](../sttp/src/com/worxbend/obs/websocket/client/transport/sttp/ReconnectingObsClient.scala) | Retries create a new scope and never replay pending requests | Callback Retry → old scope closes → backoff → new generation |
| [ObsReadService.scala](../server/src/com/worxbend/obs/websocket/client/server/ObsReadService.scala) | HTTP version reads open their own connection | HTTP handler → scoped client → typed GetVersion → close |
| [Quickstart.scala](../examples/src/com/worxbend/obs/websocket/client/examples/Quickstart.scala) | At the initial review, CLI failures only wrote stderr; subsequently corrected | Current path: `Left` → stderr → exit status 1 |

## Checks executed for this change

| Command or check | Result |
| --- | --- |
| `./mill --no-server integration.test.compile` | Passed; ten ordinary Scala documentation snippets compiled, including the nested guide |
| `./mill --no-server examples.test` | Passed; five tests, zero failures or ignored tests, using a local WebSocket peer |
| `./mill --no-server site.build` | Passed; 15 documentation pages and three API references; local links/assets checked under `/obs-websocket-client/` |
| `python3 -m unittest discover -s tools -p 'test_*.py'` | Passed; 19 tests, including nested links, companion embedding, frontmatter, and stale diagram rejection |
| Mermaid CLI 11.12.0 rendering | All four Mermaid diagrams parsed and rendered as SVG |
| Local headless Chromium at 1440px and 390px | Four SVGs loaded; embedded companion visible; search and theme controls exercised; no JavaScript errors or whole-page horizontal overflow |
| `git diff --check` | Passed |

The browser check used a local preview under the repository URL prefix. Desktop and mobile screenshots were inspected locally; this is not a Pages deployment check. Runtime deprecation warnings from Mill/dependencies remain visible. The full production test suite, coverage gate, live OBS tests, publication, and remote CI status were not rerun or reverified for this documentation change; earlier evidence remains in [IMPLEMENTATION.md](../IMPLEMENTATION.md).

## Documentation maintenance

The offline renderer now handles nested pages, source-relative links, guide frontmatter, and an empty `scala file=...` fence that embeds the tested example. The snippet compiler discovers nested Markdown guides. Four static SVGs carry source hashes, so a changed Mermaid fence requires a fresh export. [Asset instructions](../assets/architecture/README.md) explain how to regenerate them without adding a Mermaid runtime to the site.

The how-to skill's sbt/mdoc conventions are adapted to the repository's required Mill build and existing snippet compiler. Its research, source review, corrections, integration, and confirming review were performed with delegated read-only research/review and documentation-only edits. The technical-report skill's optional companion `ops/evidence-finding-path.md` was absent locally; the evidence table above applies the evidence → finding → path requirement directly.
