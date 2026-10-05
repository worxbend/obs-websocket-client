# Events and ownership

## Typed subscriptions

Each generated event companion exposes a selector. The subscription retains its bounded queue and scoped ownership while `next` and `flow` return the selected event type:

```scala
import com.worxbend.obs.websocket.client.Next
import com.worxbend.obs.websocket.client.protocol.events.CurrentProgramSceneChanged

val result = session.withEvents(CurrentProgramSceneChanged.selector): events =>
  events.next() match
    case Next.Item(event) => event.sceneName
    case _                => "stream ended"
```

The callback waits for a future matching event. Configure the corresponding server event intent before subscribing; a local selector does not change OBS's subscription mask. Typed payload projection and selection run on consumers, with no user handler on the session dispatcher. `next()` is tri-state: `Next.Item(event)` delivers a value, failures surface as `Next.Failed` with their concrete error, and `Next.Ended` is clean termination. On clean closure, event and diagnostic `flow` streams complete silently without a trailing failure. When the session itself fails, both event and diagnostic subscriptions receive the concrete session failure once — as `Next.Failed(error)`, or as `Left(error)` from diagnostics `next()` — before their streams complete.

## Optional sampling and diagnostics

`ObsSubscription.withLatestBy(interval, maxKeys)(key)(use)` provides explicit latest-per-key windows for telemetry. The sampler owns a worker inside the callback scope. It retains at most `maxKeys` keys and one queued output window. Each `EventWindow` reports coalesced events and evicted keys; `droppedWindows` reports replaced output windows and `droppedEvents` reports source-queue loss. Source termination discards the unfinished window and ends the sampled stream. An exception thrown by the user key function terminates the sampled stream with `Next.Failed(ObsError.InternalError)` carrying the cause's class name; exception message text is never embedded. Keep ordinary ordered subscriptions for control events that must all be observed.

`session.withDiagnostics(capacity)(use)` exposes metadata through `next` or `flow`: logical JSON byte traffic, request duration/outcome, and connection state transitions. It excludes request/response payloads, URI, credentials, and server rejection comments. Slow observers drop new diagnostic records, counted by `droppedDiagnostics`, without blocking the session. Wire counters remain available through `session.statistics`; request success means a successful wire exchange and can still be followed by a typed payload decode failure.

When a resolved password would authenticate over plaintext `ws` to a non-loopback host, each diagnostic subscriber additionally receives a one-time `SessionDiagnostic.PlaintextCredentials` notice at subscription time. That condition is constant for the session's lifetime, so it is delivered as a greeting rather than in the future-record stream; the notice carries no host or URI. Use `wss` for non-loopback OBS instances.

Traffic counts measure encoded UTF-8 JSON at the session boundary, including handshake/discovery traffic handled by the session. They exclude WebSocket framing, Ping/Pong, HTTP headers, TLS overhead, and TCP retransmissions. They are application metrics rather than network-interface byte counters. Diagnostic subscriptions observe future transitions; use `session.state` for a snapshot.

`completedRequests` counts finished exchange attempts, including startup discovery, timeouts, and cancellation; `failedRequests` counts their nonsuccessful outcomes. Requests rejected at registration — capability or typed validation refusals, a saturated pending-request budget — never reached the writer and are excluded from both counters. Raw request validation performed during registration is included. Sent byte counts include successful transport sends; received byte counts are recorded before protocol decoding.

Use `session.withEvents(eventTypes)(use)` to register an independent broadcast subscription. An empty set accepts every event. Each subscriber observes future matching events in receive order; subscriptions do not replay history.

## Overflow

The default `OverflowPolicy.Fail` terminates only the affected subscription with an `ObsError.Overflow`. A slow consumer never blocks request responses. Explicit `DropNewest` and `DropOldest` policies provide a loss count through `subscription.droppedEvents`; use these only where loss is acceptable. The loss count survives session failure and close, so diagnostics remain readable after termination.

## Representations

`session.withEvents` decodes known event types into their generated classes; an event type the pinned catalog does not know arrives as `UnknownEvent(eventType, eventData)`. `session.withRawEvents` delivers every matching event — known or not — as `UnknownEvent` with the complete wire payload, preserving fields newer than the catalog. Known events are still validated on arrival; a malformed known event fails the session regardless of representation.

Normal-volume event categories are enabled by default. High-volume meter events require an explicit `EventSubscriptions.fromLong` mask. `session.reidentify(mask)` queues a server mask change. Success means queued; the uncorrelated `Identified` acknowledgement is validated asynchronously.

## Flows

`subscription.flow` is an Ox `Flow[Either[ObsError, Event]]`. It runs in the consuming caller's scope. Filter and aggregate through Ox flows; user callbacks never execute on the socket reader or session state actor.

## Cleanup

Each backend adapter's entrypoint closes its own backend resources (JDK client, OkHttp dispatcher, effect runtime, or actor system). `withBackend(backend, config, abortConnection)` leaves an injected backend owned by its caller. Its required, prompt, idempotent callback must force-close the individual connection without closing a shared backend. The session closes the socket in the scope body before joining the reader. The default entrypoint force-shuts down its owned JDK client after a bounded Close attempt, including when a peer never acknowledges close. Adapter-specific teardown behavior is documented in [choosing a WebSocket backend](guides/backends.md).

A session or subscription must not escape its callback. Cancellation of one request removes that pending request; it must not close another caller's connection.

## Opt-in reconnect

`SttpObsClient.connect` always owns a single connection. Use
`ReconnectingObsClient.run` to explicitly enable bounded reconnect with
exponential backoff and jitter. Every backend adapter ships the same wrapper
(`OkHttpReconnectingObsClient`, `ZioReconnectingObsClient`,
`Fs2ReconnectingObsClient`, `PekkoReconnectingObsClient`) over the shared
`reconnect.Reconnect.run` loop in core; the sttp variant is shown below. `ReconnectPolicy.create` validates the retry
budget, initial and maximum delay, and jitter fraction. Tests inject
`ReconnectTiming` for deterministic timing; live backoff uses interruptible Ox
sleep.

Each successful connection invokes the application callback with a new
`ConnectionGeneration`. The previous session and its pending requests have
already terminated before the next attempt opens. The password provider is
called afresh during each new handshake.

The callback returns one of three explicit decisions:

- `Complete(value)` closes the connection and returns the value.
- `Stop(error)` closes the connection and returns the error without retrying.
- `Retry(error, desiredSubscriptions)` closes the connection, then considers a
  fresh attempt under the retry policy. The next handshake restores the supplied
  server event mask. Recreate local `withEvents` subscriptions inside the new
  generation callback.

**A generation callback runs again after `Retry`.** Structure it around event
consumption or recovery, and keep already-completed mutations outside that
callback. The library does not retain or replay requests or batches. A request
that lost its response can have an uncertain server-side outcome; deciding
whether to issue a new mutation remains the application's responsibility.

```scala
import com.worxbend.obs.websocket.client.*
import com.worxbend.obs.websocket.client.protocol.Event
import com.worxbend.obs.websocket.client.reconnect.*
import com.worxbend.obs.websocket.client.transport.sttp.*

// Wait for one future scene-change event, retrying transient disconnections.
def nextSceneChange(config: ObsConfig): Either[ObsError, Event] =
  ReconnectPolicy.create().flatMap: policy =>
    ReconnectingObsClient.run(config, policy): (_, session) =>
      session.withEvents(Set("CurrentProgramSceneChanged"))(_.next()) match
        case Right(Next.Item(event)) => ReconnectDecision.Complete(event)
        case Right(Next.Failed(error)) => ReconnectDecision.Retry(error, config.eventSubscriptions)
        case Right(Next.Ended) => ReconnectDecision.Retry(ObsError.Closed, config.eventSubscriptions)
        case Left(error) => ReconnectDecision.Retry(error, config.eventSubscriptions)
```

The optional `onNotice` callback reports `Connected`, `EventGap`,
`RetryScheduled`, and `Reconnected`. Notices execute on the calling thread;
they are not OBS events and do not imply that missed events can be recovered.
`EventGap` names the generation that just failed and fires only when a retry
actually follows, immediately before its `RetryScheduled`. A notice callback
that throws surfaces as `Left(ObsError.InternalError)`; interruption still
propagates so cancellation stays responsive.
Generation numbers identify attempts within one `run`, including unsuccessful
attempts. The retry budget and backoff delay progression restart whenever a
generation's callback completes: a completed callback means the connection was
healthy, so only consecutive attempts that fail before reaching the callback
consume the cumulative budget.

Authentication errors, incompatible protocols, malformed known messages, and
OBS application close codes are terminal. In particular, `SessionInvalidated`
(4011) never retries. Deterministic local conditions never retry either:
oversized messages (`MessageTooLarge`), unsupported binary frames
(`UnsupportedMessage`), invalid configuration, and internal invariant
violations (`InternalError`) recur identically after reconnect. Transient
transport failures, timeouts, and selected standard WebSocket shutdown/restart
codes (1001, 1006, 1011, 1012, 1013) can retry. Ordinary normal closure
(1000), explicit application `Stop`, exhausted retry budgets, and interruption
terminate the run. With `SttpOptions.readIdleTimeout` set, a half-open
connection that stops delivering wire traffic fails with a retryable timeout
instead of letting subscriptions stall silently, so this classification
engages; see [requests](requests.md#deadlines-and-backpressure).

For `withBackend`, configure a finite connection/upgrade deadline on the injected
backend itself. The JDK-client-backed adapters (sttp, zio, fs2) shield acquisition from
interruption until that backend deadline because the underlying JDK client can otherwise
leave an upgrade future running; the other adapters shield acquisition the same way for
uniform behavior. Cancellation can consequently wait for the backend's deadline. The default `connect`
entrypoint configures this bound from `ObsConfig.connectionTimeout`.
