# StudioMonitor observation policy

Run `./mill --no-server examples.runMain com.worxbend.obs.websocket.client.examples.StudioMonitor`.
The first argument overrides `ws://localhost:4455`; `OBS_WS_PASSWORD` supplies optional authentication.
This example performs read-only requests and subscribes to stream, recording, and OBS exit events.

## Quiet is healthy

`StudioMonitor.run` defaults to `SttpOptions()` with **no read-idle timeout**.
OBS can legitimately emit no application events for an arbitrarily long time.
The monitor makes no timer-driven application requests. Its reads are triggered by
subscription startup, relevant stream transitions, or local overflow recovery.

An explicitly supplied `SttpOptions(readIdleTimeout = Some(...))` is still honored:
it terminates receives without a completed JSON message even when the peer is
healthy. Ping/Pong traffic need not reset that outer receive deadline. It is an idle policy,
not proof of transport failure and not a heartbeat. Transport ping/pong, when
provided by a chosen backend, is a separate policy. Without it or an explicit
idle deadline, a half-open connection can remain undetected until transport I/O
fails; this example does not claim otherwise.

## Bounded event-driven work

`StudioMonitor.MonitorOptions` configures:

- `pendingCapacity = 32`: maximum pending state event lines (minimum 2).
- `maxOverflowRecoveries = 3`: cumulative local subscription recoveries for one
  invocation (0 disables recovery). The budget does not reset after a refresh.
- `shutdownTimeout = 1.second`: cooperative worker-drain budget after OBS exit
  or clean subscription completion.

An independent control reader drains the subscription without executing requests
or reporting callbacks. One scoped worker performs snapshots and reporting in
sequence. Normal-volume event lines retain their wording and retained ordering.
Events already queued in a drained worker batch share one health request, rather
than allocating one request task per event. There is at most one request in flight.
When pending lines reach capacity, newer state replaces an older line of the same
kind; a `monitor coalesced N pending state events` line makes the loss explicit.
The monitor is a bounded live observation, not an audit log of every transition.

A local `Overflow("event subscription")` cancels old-generation work, releases
the failed subscription, recreates it, then authoritatively reads both
`GetStreamStatus` and `GetRecordStatus`. Recovery prints current stream/recording
flags and health, without fabricating an OBS transition enum or recording path.
State events drained during recovery are not replayed as state lines. They retain
one coalesced invalidation for a follow-up authoritative read: a stream change can
arrive after its response but before the recording response completes. `ExitStarted`
remains a control signal throughout.
After any gap, subsequent stream/record events act only as invalidations for
coalesced authoritative reads, not as state lines folded over the snapshot:
even a new subscription's buffered event might predate its refresh. This explicit
post-loss reporting mode avoids claiming a reliable ordering boundary that OBS
does not supply. Reads still happen only on events, never on a timer.
OBS offers no atomic cross-output snapshot or event revision, so this is not a
transactional guarantee of simultaneous stream and recording state.

Ordinary health-read failures remain `stream status unavailable: ...` lines.
A failed authoritative refresh, exhausted overflow budget, or transport/protocol
failure terminates the monitor. OBS exit still works while a request or reporter
is stalled: it allows the bounded drain, then returns a shutdown timeout and
cancels cooperative work. A callback that ignores interruption can delay scope
teardown; arbitrary blocking user code cannot be made safely preemptible.

`StudioObservation` is private to these examples. Its small source seam permits
barrier-driven regression tests for bounded work, control responsiveness,
resubscription, stale-work suppression, recovery failure, and overload exhaustion.
The socket tests additionally cover the default quiet policy and explicit idle
policy. Run the integrated examples tests with `./mill --no-server examples.test`.
