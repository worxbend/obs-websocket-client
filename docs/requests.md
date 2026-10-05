# Requests and failures

Generated request classes live in `com.worxbend.obs.websocket.client.protocol.requests`. A `Request[A]` determines its response type. The session discovers available request names using `GetVersion` immediately after identification. Typed requests absent from the discovered capability set are rejected locally with `ObsError.UnsupportedRequest` — an empty capability set rejects every typed request. `RawRequest` entries bypass this check and reach the server.

For a complete read-only workflow with a runnable companion, see [Connect to OBS and read version and scenes](guides/read-version-and-scenes.md).

## Optional and nullable fields

Generated optional request fields use `Field.Missing`, `Field.Null`, or `Field.Value(value)`. Missing values are omitted. Explicit null remains distinct on the wire. Required nullable response fields use `Option`. The generator keeps documented numbers as exact `BigDecimal`; it does not guess that every number is an integer.

Whether a field takes a plain value or `Field.Value` is schema-driven, not per-request: upstream marks the field optional or not. `CreateScene(sceneName = ...)` takes a plain `String` because the field is required, while `SetCurrentProgramScene(sceneName = Field.Value(...))` wraps it because upstream marks `sceneName` optional there (either name or UUID may identify the scene). The same name can therefore appear with different shapes across requests. A plain value is also accepted for an optional field and means `Field.Value`, courtesy of the `Field` companion conversion, so `SetCurrentProgramScene(sceneName = "Studio")` compiles; `Missing` and `Null` stay explicit.

The upstream catalog describes some nested values only as Object or Array<Object>. Generated fields remain `JsonObject` or `Vector[JsonObject]`. Read a property through validated accessors, or import `protocol.models.TypedPayloads.*` for typed views of scenes, inputs, scene items/transforms, monitors, filters, outputs/flags, transitions, property items, canvases, and volume meters. Views validate known fields and retain the original object through `raw`/`toJson`, including unknown keys. Optional version-added fields distinguish `Field.Missing`, `Field.Null`, and `Field.Value`.

## Category APIs and typed views

All 147 catalog requests also have generated category methods. Categories include `general`, `scenes`, `sceneItems`, `inputs`, `sources`, `filters`, `outputs`, `record`, `stream`, `transitions`, `mediaInputs`, `configuration`, `canvases`, and `ui`. They delegate to the same session request method.

```scala
import com.worxbend.obs.websocket.client.protocol.models.TypedPayloads.*

val result = session.scenes.getSceneList().map(_.typedScenes)
```

The outer `Either` describes the request outcome; the inner `Either` describes the nested model projection. This lets a caller distinguish connection failures from an incompatible nested payload shape.

## Expected errors

`ObsError` distinguishes configuration, authentication, protocol negotiation, malformed payload, local invalid requests, unexpected messages, oversized messages, unsupported incoming frames, OBS request rejection, timeout, bounded-capacity overflow, unsupported request, transport closure, ambiguous batch outcome, internal invariant violations, and closed session failures.

The protocol module reports structural decode failures as `ProtocolError(path, message)`. It is part of the public `Request` trait so custom request types can compose the generated decoders; payload contents and credentials are never included. The session maps local catalog validation failures to `ObsError.InvalidRequest` and incoming decode failures to `ObsError.MalformedPayload`, so session operations return `ObsError` while independent model/helper validation retains `ProtocolError`.

An OBS rejection preserves request type, request ID, status code, and optional comment. Do not log complete request/settings objects. Configuration rendering redacts the password provider and shows the URI: validation forbids credentials, query, and fragment components, so a valid URI never carries secrets, and unvalidated copies still have those components stripped defensively. Transport diagnostics omit peer-controlled exception text.

Connection URIs must use `ws` or `wss` with a host, and must omit userinfo, query parameters, and fragments. Use the password provider for OBS authentication and validated handshake headers for an intermediary's header-based authentication. A configured password over plaintext `ws` to a non-loopback host surfaces a `SessionDiagnostic.PlaintextCredentials` warning (see [scoped events](events.md#optional-sampling-and-diagnostics)); prefer `wss` off-loopback.

A `MessageTooLarge` failure on an outgoing request or batch is a deterministic local rejection: nothing was written to the socket, only the offending operation fails, the session stays alive, and a rejected batch is never reported as an ambiguous outcome. `UnsupportedMessage` (for example a binary frame in JSON mode) and an inbound `MessageTooLarge` fail the session. `InternalError` reports a violated library invariant — a bug or a broken injected dependency — never a user configuration problem. A defect inside the client's own reader or writer worker also fails the session with a non-retryable `InternalError` naming the cause's class, not a retryable `Transport` failure.

During identification, the OBS close codes 4009, 4010, and 4011 map to `Authentication`, `IncompatibleProtocol`, and `Authentication` (session invalidated) respectively. Once the session is ready, the same codes keep their transport classification, and every one of them remains non-retryable.

Retry classification for the opt-in reconnect entrypoint: transport failures without a close code, transient close codes (1001, 1006, 1011, 1012, 1013), and timeouts may retry. Deterministic conditions — `MessageTooLarge`, `UnsupportedMessage`, `InvalidConfiguration`, `InvalidRequest`, `InternalError`, malformed payloads, authentication and protocol failures, request rejections, and the terminal OBS close codes (1000, 4009, 4010, 4011) — recur identically after reconnect and never retry.

## Deadlines and backpressure

Connection, identification, request, and shutdown deadlines are configured separately. Pending requests, the outgoing queue, message size, and each subscriber buffer are bounded. Saturation returns a typed overflow error. Concurrent responses correlate by request ID, independent of arrival order. Late or duplicate responses cannot revive completed requests.

`RequestOptions` overrides the session default for a complete exchange, including actor registration and response waiting. It applies to `request`, `rawRequest`, `batch`, and `typedBatch`. Use `withOptions` to apply it through category methods:

```scala
import com.worxbend.obs.websocket.client.RequestOptions
import scala.concurrent.duration.*

val result = session.withOptions(RequestOptions(timeout = Some(2.seconds))).general.getVersion()
```

Budgets bound registration and response waiting. Local validation, typed decoding, and required cancellation cleanup are additional work, so this is not a hard end-to-end wall-clock return guarantee.

Each backend adapter separately enforces its options' write deadline for data and Pong writes (for example `SttpOptions.writeTimeout`); `ObsConfig.shutdownTimeout` bounds the Close attempt. A write timeout aborts that connection; it never replays the operation. Caller-owned backends must provide an abort hook that promptly unblocks both reads and writes. See [choosing a WebSocket backend](guides/backends.md) for the available adapters.

`SttpOptions.readIdleTimeout` (default `None`) is opt-in liveness detection. When set, any receive that waits longer than the deadline for wire traffic — including a stall between the fragments of one message — fails with a retryable `ObsError.Timeout` and destroys the connection. This detects half-open connections (a lost FIN, a NAT timeout) that would otherwise stall reads forever, so the reconnect entrypoint can classify and replace them. The tradeoff is that a genuinely quiet connection whose normal traffic gap exceeds the chosen deadline also fails.

For an owned connection on the default backend, `SttpObsClient.connect(config, options, clientOptions)` accepts `SttpOptions` and `JdkClientOptions`. The okhttp adapter accepts the same transport options under the `transport.okhttp.OkHttpOptions` alias plus `OkHttpClientOptions`, and the JDK-backed zio and fs2 adapters accept `JdkClientOptions` as well; pekko's entrypoint takes `PekkoOptions` only. Construct custom handshake headers with `HandshakeHeaders.create`; reserved WebSocket/HTTP framing headers and invalid values are rejected, and rendering is redacted. `JdkClientOptions` accepts optional `ProxySelector` and `SSLContext` values. `withBackend` takes transport options but leaves TLS/proxy ownership with its supplied backend. Reconnect uses the same transport settings on each fresh generation.

## Inspect a response once

`requestEnvelope` retains the raw response data and its typed decode result from the same exchange. Server rejections and transport failures remain outer errors. A successful server response with an incompatible typed shape returns an envelope whose `decoded` member is `Left`; `raw` remains available. This is especially useful for operations that must not be issued again just to inspect their result.

```scala
import com.worxbend.obs.websocket.client.protocol.requests.GetVersion

val result = session.requestEnvelope(GetVersion()).map(envelope => (envelope.raw, envelope.decoded))
```

## Explicit readiness recovery

`requestWhenReady` retries only an explicit OBS NotReady rejection (207), only for names in `ReadinessPolicy.supportedRequests`. Raw requests, batches, and mutations are excluded. `maxAttempts` includes the first attempt, and the entire operation shares the configured request budget, including delays. `ObsConfig.readiness = Some(policy)` enables the same bounded recovery for startup capability discovery. Both behaviors are opt-in; authentication failures, timeouts, and uncertain writes are never readiness retries.

## Raw extensions

`session.rawRequest("VendorOperation", data)` and `RawRequest("VendorOperation", data)` are the escape hatches for vendor extensions and requests added by peers newer than the pinned catalog. Both bypass the capability check and reach the server, which decides whether to execute them. A `RawRequest` naming a catalog request still validates its fields against the catalog shape. Both forms keep the same correlation, timeout, size, and connection ownership rules. Unknown event types are delivered as `UnknownEvent`; see [Events and ownership](events.md).

## Batches

`session.typedBatch((BatchCall(GetVersion()), BatchCall(GetSceneList())))` retains each response type in its result tuple. `session.batch` exposes raw individual results. Choose serial realtime or serial frame execution and an explicit halt/continue policy. Nonempty parallel batches are rejected locally: supported OBS implementations cannot reliably associate their results with submitted requests. Typed batch entries are capability-checked like single typed requests; `RawRequest` entries bypass that check.

A batch has no rollback and is not a transaction. A timeout or disconnect after submission is ambiguous. A batch rejected locally before anything reached the socket — invalid fields or an oversized payload — is not ambiguous and returns that precise error. Outstanding operations are never replayed automatically. Treat not-executed results separately from rejected requests.
