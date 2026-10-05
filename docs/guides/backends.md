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

Each snippet is compile-checked by the documentation build; it does not connect to OBS. Transport options mirror each other across adapters: `SttpOptions`, `ZioOptions`, `Fs2Options`, and `PekkoOptions` all carry a write deadline, handshake headers, and an opt-in `readIdleTimeout`. The okhttp adapter accepts the same type under the alias `transport.okhttp.OkHttpOptions` and adds `OkHttpClientOptions` for the client itself. The JDK-backed adapters (sttp, zio, fs2) accept `transport.JdkClientOptions` (proxy, TLS) for their owned JDK client; the pekko adapter has no equivalent because its backend is Pekko-native rather than JDK-based.

## Ownership

- `connect` builds and closes a private backend per call: JDK client (sttp, and internally for zio/fs2), OkHttp client plus dispatcher executor (okhttp), a cats-effect `Dispatcher` allocated and released per call (fs2), and a private `ActorSystem` that is terminated and awaited (pekko). zio allocates nothing beyond the connection itself.
- `withBackend(backend, config, abortConnection)` on each module leaves an injected backend — and for pekko, the caller's actor system — owned by its caller. The supplied `abortConnection` must promptly and idempotently force-close only this connection and unblock pending reads and writes; it must tolerate repeated invocation.
- zio and fs2 run effects on the shared default runtimes (`Runtime.default`, `IORuntime.global`), which are never shut down; pekko's `connect` proves termination through `whenTerminated`.
- Reconnect wrappers exist per adapter: `ReconnectingObsClient` (sttp), `OkHttpReconnectingObsClient`, `ZioReconnectingObsClient`, `Fs2ReconnectingObsClient`, and `PekkoReconnectingObsClient`, all delegating to `reconnect.Reconnect.run` in core.

## Behavioral differences

Adapters share the session engine, deadlines, byte limits, and error taxonomy, but the underlying clients differ at the edges:

- **okhttp**: an HTTP upgrade rejection surfaces as `ObsError.Transport("WebSocket connection failed")` rather than `"WebSocket upgrade rejected"`, because OkHttp reports it as a generic `ProtocolException`. Abortion gives a queued graceful Close a bounded grace to reach the wire before forcing cancellation.
- **zio / fs2**: sttp's async JDK backend recovers a failed handshake into a delivered response, so upgrade rejections always read `"WebSocket upgrade rejected"` through the response-body path; a thrown backend exception means connection failure. A foreign effect interrupted mid-flight completes on runtime worker threads the Ox scope cannot join; the connection itself is still destroyed promptly on abort.
- **pekko**: pekko-http answers Ping frames at the protocol layer (the transport never sees them). A peer close with an error code surfaces the real code via `PeerClosedConnectionException`, while a 1000/1001 close completes the flow and surfaces as synthetic 1000. Note pekko-http's `CloseCodes.isValid` accepts only 1000–1003, 1007–1011, and 3000–4999: a peer sending 1012/1013 is itself closed with protocol error 1002. Shutting the owned system down is aggressive: teardown can end in a TCP reset rather than a clean FIN.
- **Clean-close surfacing**: when a peer closes with an empty close payload, the JDK-backed adapters (sttp, okhttp, zio, fs2) surface `ObsError.Transport("WebSocket closed", closeCode = Some(1005))` — the protocol-level "no status received" code — while pekko surfaces the synthetic `Some(1000)` noted above. Both are terminal (non-retryable) classifications, so the difference is observable in error reporting only.
- **sttp**: upgrade rejection maps to `"WebSocket upgrade rejected"` through the JDK's dedicated handshake exception; teardown finishes a bounded Close attempt before force-shutting the owned JDK client down.

## Calling from a ZIO or cats-effect application

The adapters present a blocking API on purpose. From an effect application, wrap every blocking entrypoint in the effect system's blocking wrapper:

```scala
import com.worxbend.obs.websocket.client.*
import com.worxbend.obs.websocket.client.protocol.requests.GetVersion
import com.worxbend.obs.websocket.client.transport.zio.ZioObsClient
import _root_.zio.ZIO

def readVersionEffect(config: ObsConfig) =
  ZIO.attemptBlocking(ZioObsClient.connect(config)(_.request(GetVersion())).flatten)
```

```scala
import cats.effect.IO
import com.worxbend.obs.websocket.client.*
import com.worxbend.obs.websocket.client.protocol.requests.GetVersion
import com.worxbend.obs.websocket.client.transport.fs2.Fs2ObsClient

def readVersionEffect(config: ObsConfig) =
  IO.blocking(Fs2ObsClient.connect(config)(_.request(GetVersion())).flatten)
```

The same applies to blocking session calls (requests, `next()`, reconnect `run`) made inside an effect: keep them inside `ZIO.attemptBlocking` / `IO.blocking`, or run the whole callback on a virtual thread. (`ZIO.blocking` instead shifts an existing effect onto the blocking executor; `IO.blocking` is the side-effect wrapper.) The bridge always uses the shared `Runtime.default` (zio) and `IORuntime.global` (fs2) regardless of your application's runtime — your runtime and the client's bridge never share worker pools, so a blocked bridge call never starves your effect scheduler. The fs2 adapter additionally allocates and releases a `Dispatcher.parallel` per `connect` call. The pekko adapter creates one `ActorSystem` per `connect`; group related work inside a single connection callback rather than opening many short-lived connections.

## Continue

Follow [connect to OBS and read version and scenes](read-version-and-scenes.md) for a runnable workflow on the default backend, [requests](../requests.md#deadlines-and-backpressure) for deadline semantics, and [events](../events.md#opt-in-reconnect) for reconnect behavior. [ADR-005](../decisions/005-backend-modules.md) records why the adapters publish separately.
