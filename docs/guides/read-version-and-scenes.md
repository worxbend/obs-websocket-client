---
id: read-version-and-scenes
title: "Connect to OBS and read version and scenes"
description: "Connect a Scala application to OBS, read its version and scene catalog, handle failures, and close the connection."
keywords:
  - "OBS"
  - "Scala 3"
  - "WebSocket"
  - "GetVersion"
  - "GetSceneList"
  - "Mill"
---

Use this guide to check which OBS instance your application reached and retrieve its scene catalog. The runnable companion prints the OBS version and scene count; its reusable function returns both response values. This workflow only reads OBS state.

## The problem

Reading an OBS instance's version and scene catalog through a raw WebSocket requires the Hello/Identify handshake, challenge authentication, request IDs, response correlation, JSON decoding, deadlines, and cleanup. For example, an event can arrive while the application waits for a scene-list response, so reading the next frame alone cannot identify the response. The library handles this coordination inside a connection callback, leaving your application to compose typed requests.

## Prepare OBS and the checkout

Enable OBS Studio's WebSocket server in the Tools menu and obtain its connection password. The usual local address is `ws://localhost:4455`. Export `OBS_WS_PASSWORD` in the environment of the shell that will run the example, using your shell's secure input or secret-management facility. Keep the password out of source files and command arguments. If authentication is disabled, leave the variable unset; the example treats a blank value as absent.

This repository is unreleased; there is no published Maven Central version to install. Its wrapper pins Mill 1.1.10 and Java 25.0.3, and the build uses Scala 3.9.0. The first run may download the pinned tools and dependencies. Run all commands from the repository root:

```sh
git clone https://github.com/worxbend/obs-websocket-client.git
cd obs-websocket-client
./mill --no-server examples.compile
```

The example depends on the local `sttp` module, which brings in `core` and `protocol`; local artifact publication is unnecessary. The okhttp, zio, fs2, and pekko backend adapters offer the same session API if your stack favors them; see [choosing a WebSocket backend](backends.md). The verified live target is OBS 30.2.3 with obs-websocket 5.5.2. Consult [compatibility](../compatibility.md) before assuming another 5.x server implements every field in the pinned schema.

## Connect and read the version

`ObsConfig` supplies the address and credentials. `PasswordProvider.fixed` accepts an optional password. `SttpObsClient.connect` supplies an `ObsSession` only after identification and capability discovery succeed:

```scala
import com.worxbend.obs.websocket.client.{ObsConfig, PasswordProvider}
import com.worxbend.obs.websocket.client.protocol.requests.GetVersion
import com.worxbend.obs.websocket.client.transport.sttp.SttpObsClient

val config = ObsConfig(
  passwordProvider = PasswordProvider.fixed(sys.env.get("OBS_WS_PASSWORD"))
)
val result = SttpObsClient.connect(config): session =>
  session.request(GetVersion())
val versionResult = result.flatten
```

`versionResult` is an `Either[ObsError, GetVersionResponse]`: success contains fields such as `obsVersion` and `obsWebSocketVersion`. The outer result reports connection/setup failures; the callback returns a second `Either` for the request. Calling `flatten` gives one error path. Each snippet here is independently compile-checked, but documentation compilation does not connect to OBS.

## Prefer TLS for non-local OBS

The default `ws` scheme is plaintext. When a password is configured and the address is not loopback — not `localhost`, not `127.0.0.0/8`, not `::1` — the OBS authentication hash crosses the network in a form an eavesdropper can replay against that session. Use `wss` for any OBS instance reached over a network, or keep plaintext `ws` confined to loopback. This is a warning, not a rejection: the session still connects, and `session.withDiagnostics` delivers a `SessionDiagnostic.PlaintextCredentials` notice to each diagnostic subscriber at subscription time so operators can detect the exposure.

## Read both values in the same session

Inside a connection callback, compose the two requests in order. In this snippet, `session` is the callback's `ObsSession`:

```scala
import com.worxbend.obs.websocket.client.protocol.requests.{GetSceneList, GetVersion}

val discovery = for
  version <- session.request(GetVersion())
  scenes <- session.request(GetSceneList())
yield (version, scenes)
```

The result is `Either[ObsError, (GetVersionResponse, GetSceneListResponse)]`. If version discovery fails, the scene request is skipped. On success, `version.obsVersion` is a string and `scenes.scenes.size` is the scene count. The scene entries themselves are `JsonObject` values; current program and preview scene names are optional fields on `GetSceneListResponse`.

The connection setup already makes its own `GetVersion` query to discover supported requests. This workflow therefore sends that internal query plus the two explicit requests above. No JSON codecs or request IDs need to be supplied by application code.

## Put it together and run

The existing [Quickstart source](../../examples/src/com/worxbend/obs/websocket/client/examples/Quickstart.scala) is the complete companion. `discover` composes the reads, `run` owns their connection, and `main` prints the result:

```scala file=examples/src/com/worxbend/obs/websocket/client/examples/Quickstart.scala
```

With OBS running and the password environment variable set, run either command from the repository root:

```sh
./mill --no-server examples.run
./mill --no-server examples.run ws://localhost:4455
```

The first command uses localhost; the second demonstrates the positional URL override. The CLI reads `OBS_WS_PASSWORD` and does not read `OBS_WS_URL`.

The companion prints `OBS <version>: <count> scenes` on success. Its automated scripted-peer test verifies this exact fixture output; your live values depend on OBS and its active scene collection:

```text
OBS 32.0: 0 scenes
```

Returning from the callback closes the socket and joins its workers. Return the response values, as this companion does, and keep all session operations inside the callback. Cleanup also runs if the callback throws: an application exception is a defect that propagates raw after cleanup, never converted into an `ObsError`; expected failures stay inside the returned `Either`.

## Handle a failed read

The companion matches the flattened `Either` and prints any `Left` to stderr with `OBS connection failed:`. That prefix also covers request failures. The command exits with status 1 on failure and 0 on success, after scoped connection cleanup. Applications embedding the library should use `run` or `discover` and handle their returned `Either` directly.

Inside a connection callback, handle an individual read with the same `Either` pattern:

```scala
import com.worxbend.obs.websocket.client.protocol.requests.GetVersion

session.request(GetVersion()) match
  case Right(version) => println(s"Connected to OBS ${version.obsVersion}")
  case Left(error) => Console.err.println(s"OBS read failed: $error")
```

| Error | What to check |
| --- | --- |
| `InvalidConfiguration` | Use a `ws://` or `wss://` address with a host and valid port; credentials do not belong in the URL. |
| `Transport` | Confirm OBS is running, its WebSocket server is enabled, and the address is reachable. |
| `Authentication` | Check the password against the selected OBS instance's WebSocket settings. |
| `Timeout` | Check reachability and responsiveness; connection, handshake, and request deadlines default to ten seconds each. |
| `UnsupportedRequest` | The server did not advertise the typed request; check its capabilities and version. |
| `RequestRejected` | Inspect the request type, OBS status code, and optional comment in the error. |
| `MalformedPayload` | Check the reported field path and the documented server/schema compatibility limits. |

This entrypoint makes one connection. Recovery that needs another connection must be arranged by the application; see [opt-in reconnect](../events.md#opt-in-reconnect) for the separate reconnect API.

## Verify the companion and continue

These repository checks use a local scripted WebSocket peer and compile the documentation snippets; neither requires a running OBS instance:

```sh
./mill --no-server examples.test
./mill --no-server integration.test.compile
```

The Quickstart companion's seven tests cover discovery values, printed success, invalid input, blank-password handling, explicit/default configuration, and the CLI exit status. The peer also checks the Close frame. These checks do not establish live compatibility; the [compatibility matrix](../compatibility.md) records the tested OBS versions and operations.

Follow the [architecture's source map](../architecture.md) to see how the sttp entrypoint, core session, and generated protocol implement this workflow. For the next application operation, see [typed requests](../requests.md) or [scoped events](../events.md).
