---
id: backends
title: "Choosing a WebSocket backend"
description: "Pick the sttp, okhttp, zio, fs2, or pekko backend adapter for the OBS WebSocket client, with coordinates, ownership, and behavioral differences."
keywords:
  - "OBS"
  - "Scala 3"
  - "WebSocket"
  - "backend"
  - "sttp"
  - "okhttp"
  - "ZIO"
  - "fs2"
  - "pekko"
---

The session engine lives in `core` behind the blocking `ObsTransport` seam; each published backend adapter implements that seam and presents the identical `ObsSession` API. Declare exactly one adapter. The coordinates below are provisional — nothing is published to Maven Central yet.

## Pick an artifact

| Artifact | Backend | Choose this when |
| --- | --- | --- |
| `obs-websocket-client-sttp` | JDK `HttpClient` (sync) | Default; no effect runtime, fewest moving parts. |
| `obs-websocket-client-okhttp` | OkHttp (sync) | You already standardize on OkHttp; still no effect runtime. |
| `obs-websocket-client-zio` | sttp ZIO backend | Your stack is ZIO. |
| `obs-websocket-client-fs2` | sttp cats-effect/fs2 backend | Your stack is cats-effect/fs2. |
| `obs-websocket-client-pekko` | sttp Pekko HTTP backend | Your stack uses Pekko/Akka-style streams. |

```text
// Mill, default backend:
mvn"com.worxbend.obs.websocket.client::obs-websocket-client-sttp:0.1.0-SNAPSHOT"
// Alternatives:
mvn"com.worxbend.obs.websocket.client::obs-websocket-client-okhttp:0.1.0-SNAPSHOT"
mvn"com.worxbend.obs.websocket.client::obs-websocket-client-zio:0.1.0-SNAPSHOT"
mvn"com.worxbend.obs.websocket.client::obs-websocket-client-fs2:0.1.0-SNAPSHOT"
mvn"com.worxbend.obs.websocket.client::obs-websocket-client-pekko:0.1.0-SNAPSHOT"
```

Every adapter brings `core` and `protocol` transitively. The effect adapters pull their runtime (ZIO, cats-effect/fs2, Pekko) transitively but keep it internal: the public API stays the blocking direct-style one on every backend.

## The shared entrypoint shape

`connect` owns the connection for exactly the callback's lifetime and returns `Either[ObsError, A]`:

```scala
import com.worxbend.obs.websocket.client.*
import com.worxbend.obs.websocket.client.protocol.requests.GetVersion
import com.worxbend.obs.websocket.client.transport.sttp.SttpObsClient

def readVersion(config: ObsConfig) =
  SttpObsClient.connect(config)(_.request(GetVersion())).flatten
```

```scala
import com.worxbend.obs.websocket.client.*
import com.worxbend.obs.websocket.client.protocol.requests.GetVersion
import com.worxbend.obs.websocket.client.transport.okhttp.OkHttpObsClient

def readVersion(config: ObsConfig) =
  OkHttpObsClient.connect(config)(_.request(GetVersion())).flatten
```

```scala
import com.worxbend.obs.websocket.client.*
import com.worxbend.obs.websocket.client.protocol.requests.GetVersion
import com.worxbend.obs.websocket.client.transport.zio.ZioObsClient

def readVersion(config: ObsConfig) =
  ZioObsClient.connect(config)(_.request(GetVersion())).flatten
```

```scala
import com.worxbend.obs.websocket.client.*
import com.worxbend.obs.websocket.client.protocol.requests.GetVersion
import com.worxbend.obs.websocket.client.transport.fs2.Fs2ObsClient

def readVersion(config: ObsConfig) =
  Fs2ObsClient.connect(config)(_.request(GetVersion())).flatten
```

```scala
import com.worxbend.obs.websocket.client.*
import com.worxbend.obs.websocket.client.protocol.requests.GetVersion
import com.worxbend.obs.websocket.client.transport.pekko.PekkoObsClient

def readVersion(config: ObsConfig) =
  PekkoObsClient.connect(config)(_.request(GetVersion())).flatten
```

Each snippet is compile-checked by the documentation build; it does not connect to OBS. Transport options mirror each other across adapters: `SttpOptions`, `ZioOptions`, `Fs2Options`, and `PekkoOptions` all carry a write deadline, handshake headers, and an opt-in `readIdleTimeout`; the okhttp adapter reuses `SttpOptions` for transport and adds `OkHttpClientOptions` for the client itself. `SttpObsClient` accepts `JdkClientOptions` (proxy, TLS) for its owned JDK client.

## Ownership

- `connect` builds and closes a private backend per call: JDK client (sttp, and internally for zio/fs2), OkHttp client plus dispatcher executor (okhttp), a cats-effect `Dispatcher` allocated and released per call (fs2), and a private `ActorSystem` that is terminated and awaited (pekko). zio allocates nothing beyond the connection itself.
- `withBackend(backend, config, abortConnection)` on each module leaves an injected backend — and for pekko, the caller's actor system — owned by its caller. The supplied `abortConnection` must promptly and idempotently force-close only this connection and unblock pending reads and writes; it must tolerate repeated invocation.
- zio and fs2 run effects on the shared default runtimes (`Runtime.default`, `IORuntime.global`), which are never shut down; pekko's `connect` proves termination through `whenTerminated`.
- Reconnect wrappers exist per adapter: `ReconnectingObsClient` (sttp), `ZioReconnectingObsClient`, `Fs2ReconnectingObsClient`, and `PekkoReconnectingObsClient`, all delegating to `reconnect.Reconnect.run` in core. The okhttp module adds no wrapper of its own; compose `Reconnect.run` with a `ReconnectConnector` over `OkHttpObsClient.connect`.

## Behavioral differences

Adapters share the session engine, deadlines, byte limits, and error taxonomy, but the underlying clients differ at the edges:

- **okhttp**: an HTTP upgrade rejection surfaces as `ObsError.Transport("WebSocket connection failed")` rather than `"WebSocket upgrade rejected"`, because OkHttp reports it as a generic `ProtocolException`. Abortion gives a queued graceful Close a bounded grace to reach the wire before forcing cancellation.
- **zio / fs2**: sttp's async JDK backend recovers a failed handshake into a delivered response, so upgrade rejections always read `"WebSocket upgrade rejected"` through the response-body path; a thrown backend exception means connection failure. A foreign effect interrupted mid-flight completes on runtime worker threads the Ox scope cannot join; the connection itself is still destroyed promptly on abort.
- **pekko**: pekko-http answers Ping frames at the protocol layer (the transport never sees them), and a peer close preserves its status code unless the code is 1000 or 1001, which completes the flow and surfaces as synthetic 1000. Shutting the owned system down is aggressive: teardown can end in a TCP reset rather than a clean FIN.
- **sttp**: upgrade rejection maps to `"WebSocket upgrade rejected"` through the JDK's dedicated handshake exception; teardown finishes a bounded Close attempt before force-shutting the owned JDK client down.

## Continue

Follow [connect to OBS and read version and scenes](read-version-and-scenes.md) for a runnable workflow on the default backend, [requests](../requests.md#deadlines-and-backpressure) for deadline semantics, and [events](../events.md#opt-in-reconnect) for reconnect behavior. [ADR-005](../decisions/005-backend-modules.md) records why the adapters publish separately.
