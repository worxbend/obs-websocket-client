# Events and ownership

Use `session.withEvents(eventTypes)(use)` to register an independent broadcast subscription. An empty set accepts every event. Each subscriber observes future matching events in receive order; subscriptions do not replay history.

## Overflow

The default `OverflowPolicy.Fail` terminates only the affected subscription with an `ObsError.Overflow`. A slow consumer never blocks request responses. Explicit `DropNewest` and `DropOldest` policies provide a loss count through `subscription.droppedEvents`; use these only where loss is acceptable. The loss count survives session failure and close, so diagnostics remain readable after termination.

## Representations

`session.withEvents` decodes known event types into their generated classes; an event type the pinned catalog does not know arrives as `UnknownEvent(eventType, eventData)`. `session.withRawEvents` delivers every matching event — known or not — as `UnknownEvent` with the complete wire payload, preserving fields newer than the catalog. Known events are still validated on arrival; a malformed known event fails the session regardless of representation.

Normal-volume event categories are enabled by default. High-volume meter events require an explicit `EventSubscriptions.fromLong` mask. `session.reidentify(mask)` changes the server mask without waiting for a nonexistent acknowledgement.

## Flows

`subscription.flow` is an Ox `Flow[Either[ObsError, Event]]`. It runs in the consuming caller's scope. Filter and aggregate through Ox flows; user callbacks never execute on the socket reader or session state actor.

## Cleanup

The sttp entrypoint closes its own backend. `withBackend` leaves an injected backend owned by its caller. The session closes the socket in the scope body before joining the reader. The sttp receive path is interruptible, including when a peer never acknowledges close.

A session or subscription must not escape its callback. Cancellation of one request removes that pending request; it must not close another caller's connection.

## Opt-in reconnect

`SttpObsClient.connect` always owns a single connection. Use
`ReconnectingObsClient.run` to explicitly enable bounded reconnect with
exponential backoff and jitter. `ReconnectPolicy.create` validates the retry
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
import com.worxbend.obs.websocket.client.transport.sttp.*

// Wait for one future scene-change event, retrying transient disconnections.
def nextSceneChange(config: ObsConfig): Either[ObsError, Event] =
  ReconnectPolicy.create().flatMap: policy =>
    ReconnectingObsClient.run(config, policy): (_, session) =>
      session.withEvents(Set("CurrentProgramSceneChanged"))(_.next()).flatten match
        case Right(event) => ReconnectDecision.Complete(event)
        case Left(error) => ReconnectDecision.Retry(error, config.eventSubscriptions)
```

The optional `onNotice` callback reports `Connected`, `EventGap`,
`RetryScheduled`, and `Reconnected`. Notices execute on the calling thread;
they are not OBS events and do not imply that missed events can be recovered.
Generation numbers identify attempts within one `run`, including unsuccessful
attempts. The retry budget applies to the complete run and does not reset after
a successful handshake.

Authentication errors, incompatible protocols, malformed known messages, and
OBS application close codes are terminal. In particular, `SessionInvalidated`
(4011) never retries. Deterministic local conditions never retry either:
oversized messages (`MessageTooLarge`), unsupported binary frames
(`UnsupportedMessage`), invalid configuration, and internal invariant
violations (`InternalError`) recur identically after reconnect. Transient
transport failures, timeouts, and selected standard WebSocket shutdown/restart
codes (1001, 1006, 1011, 1012, 1013) can retry. Ordinary normal closure
(1000), explicit application `Stop`, exhausted retry budgets, and interruption
terminate the run.

For `withBackend`, configure a finite connection/upgrade deadline on the injected
backend itself. The client shields acquisition from interruption until that backend
deadline because sttp's JDK backend can otherwise leave an upgrade future running.
Cancellation can consequently wait for the backend's deadline. The default `connect`
entrypoint configures this bound from `ObsConfig.connectionTimeout`.
