# Requests and failures

Generated request classes live in `com.worxbend.obs.websocket.client.protocol.requests`. A `Request[A]` determines its response type. The session discovers available request names using `GetVersion` immediately after identification. Typed requests absent from the discovered capability set are rejected locally with `ObsError.UnsupportedRequest` — an empty capability set rejects every typed request. `RawRequest` entries bypass this check and reach the server.

## Optional and nullable fields

Generated optional request fields use `Field.Missing`, `Field.Null`, or `Field.Value(value)`. Missing values are omitted. Explicit null remains distinct on the wire. Required nullable response fields use `Option`. The generator keeps documented numbers as exact `BigDecimal`; it does not guess that every number is an integer.

The upstream catalog describes some nested values only as Object or Array<Object>. Those values remain `JsonObject` or `Vector[JsonObject]`. Read a property through validated `string`, `int`, `array`, or `obj` accessors. Unknown object fields are retained.

## Expected errors

`ObsError` distinguishes configuration, authentication, protocol negotiation, malformed payload, unexpected messages, oversized messages, unsupported incoming frames, OBS request rejection, timeout, bounded-capacity overflow, unsupported request, transport closure, ambiguous batch outcome, internal invariant violations, and closed session failures.

The protocol module reports structural decode failures as `ProtocolError(path, message)`. It is part of the public `Request` trait so custom request types can compose the generated decoders; payload contents and credentials are never included. The session maps every `ProtocolError` to `ObsError.MalformedPayload` internally, so session operations only ever return `ObsError`.

An OBS rejection preserves request type, request ID, status code, and optional comment. Do not log complete request/settings objects. Configuration rendering redacts both the URI and password provider, including invalid configurations and query tokens. Transport diagnostics omit peer-controlled exception text.

A `MessageTooLarge` failure on an outgoing request or batch is a deterministic local rejection: nothing was written to the socket, only the offending operation fails, the session stays alive, and a rejected batch is never reported as an ambiguous outcome. `UnsupportedMessage` (for example a binary frame in JSON mode) and an inbound `MessageTooLarge` fail the session. `InternalError` reports a violated library invariant — a bug or a broken injected dependency — never a user configuration problem.

Retry classification for the opt-in reconnect entrypoint: transport failures without a close code, transient close codes (1001, 1006, 1011, 1012, 1013), and timeouts may retry. Deterministic conditions — `MessageTooLarge`, `UnsupportedMessage`, `InvalidConfiguration`, `InternalError`, malformed payloads, authentication and protocol failures, request rejections, and the terminal OBS close codes (1000, 4009, 4010, 4011) — recur identically after reconnect and never retry.

## Deadlines and backpressure

Connection, identification, request, and shutdown deadlines are configured separately. Pending requests, the outgoing queue, message size, and each subscriber buffer are bounded. Saturation returns a typed overflow error. Concurrent responses correlate by request ID, independent of arrival order. Late or duplicate responses cannot revive completed requests.

## Raw extensions

`session.rawRequest("VendorOperation", data)` and `RawRequest("VendorOperation", data)` are the escape hatches for vendor extensions and requests added by peers newer than the pinned catalog. Both bypass the capability check and reach the server, which decides whether to execute them. A `RawRequest` naming a catalog request still validates its fields against the catalog shape. Both forms keep the same correlation, timeout, size, and connection ownership rules. Unknown event types are delivered as `UnknownEvent`; see [Events and ownership](events.md).

## Batches

`session.typedBatch((BatchCall(GetVersion()), BatchCall(GetSceneList())))` retains each response type in its result tuple. `session.batch` exposes raw individual results. Choose serial realtime or serial frame execution and an explicit halt/continue policy. Nonempty parallel batches are rejected locally: supported OBS implementations cannot reliably associate their results with submitted requests. Typed batch entries are capability-checked like single typed requests; `RawRequest` entries bypass that check.

A batch has no rollback and is not a transaction. A timeout or disconnect after submission is ambiguous. A batch rejected locally before anything reached the socket — invalid fields or an oversized payload — is not ambiguous and returns that precise error. Outstanding operations are never replayed automatically. Treat not-executed results separately from rejected requests.
