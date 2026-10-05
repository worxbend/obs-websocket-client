package com.worxbend.obs.websocket.client.examples

import com.worxbend.obs.websocket.client.protocol.Event
import com.worxbend.obs.websocket.client.protocol.events.{ExitStarted, RecordStateChanged, StreamStateChanged}
import com.worxbend.obs.websocket.client.protocol.requests.{GetStreamStatus, GetStreamStatusResponse}
import com.worxbend.obs.websocket.client.transport.sttp.{SttpObsClient, SttpOptions}
import com.worxbend.obs.websocket.client.{Next, ObsConfig, ObsError, ObsSession, PasswordProvider}
import scala.concurrent.duration.*

/** Event-driven studio monitor. Blocks on a subscription and reacts to each event; it never polls on a timer.
  *
  * OBS pushes no frame-drop metric events: dropped frames, congestion, and sent bytes exist only in
  * [[GetStreamStatus]] responses. The event-driven way to watch stream health is therefore to react to
  * [[StreamStateChanged]] transitions and snapshot [[GetStreamStatus]] exactly when the stream reports trouble
  * (reconnecting) or finishes (stopped), never on an interval.
  *
  * All watched events are normal-volume, so the default `EventSubscriptions.normal` mask already enables them.
  * High-volume feeds such as `InputVolumeMeters` (audio levels every 50 ms) additionally require their
  * explicit subscription bit in `ObsConfig.eventSubscriptions`; see `OverflowPolicy` and
  * `ObsSubscription.withLatestBy` for consuming those without unbounded buffers.
  */
object StudioMonitor:
  /** Events a studio dashboard reacts to. */
  val watchedEvents: Set[String] = Set("StreamStateChanged", "RecordStateChanged", "ExitStarted")

  /** Wire values of `outputState` that mark stream trouble or completion. The generated protocol models output
    * states as plain `String`, so these constants are the single reference point shared with tests.
    */
  val StreamReconnectingState = "OBS_WEBSOCKET_OUTPUT_RECONNECTING"
  val StreamStoppedState      = "OBS_WEBSOCKET_OUTPUT_STOPPED"

  /** Stream states where an on-demand [[GetStreamStatus]] snapshot carries actionable health data. */
  def snapshotWorthy(state: String): Boolean =
    state == StreamReconnectingState || state == StreamStoppedState

  /** One operator-log line per event; `None` means the event needs no line. */
  def describe(event: Event): Option[String] = event match
    case StreamStateChanged(active, state)       => Some(s"stream: $state (active=$active)")
    case RecordStateChanged(active, state, path) =>
      Some(s"record: $state (active=$active)" + path.fold("")(file => s" -> $file"))
    case _: ExitStarted => Some("obs is shutting down")
    case _              => None

  /** Dropped-frame counters from a [[GetStreamStatusResponse]] snapshot. OBS semantics guarantee
    * `outputSkippedFrames <= outputTotalFrames`; there is deliberately no clamp, so a violating server
    * surfaces in the rendered line instead of being silently corrected.
    */
  def describeStatus(status: GetStreamStatusResponse): String =
    val dropped = status.outputSkippedFrames
    val total   = status.outputTotalFrames
    val ratio   =
      if total > 0 then (dropped / total * 100).setScale(2, BigDecimal.RoundingMode.HALF_UP) else BigDecimal(0)
    s"stream health: skipped $dropped of $total frames ($ratio%), " +
      s"congestion ${status.outputCongestion}, ${status.outputBytes} bytes sent"

  /** One event-triggered [[GetStreamStatus]] read; failures degrade to a log line instead of ending the monitor. */
  private def snapshot(session: ObsSession, report: String => Unit): Unit =
    session.request(request = GetStreamStatus()) match
      case Right(status) => report(describeStatus(status = status))
      case Left(error)   => report(s"stream status unavailable: $error")

  /** React to events until OBS exits, the session ends, or the subscription fails. A baseline snapshot is taken when
    * the subscription goes live; after that, a status snapshot is requested only when a stream state event makes it
    * meaningful, so every request is triggered by an event.
    *
    * The event loop blocks in `ObsSubscription.next` without an intrinsic deadline: callers that do not go through
    * [[run]] must bound the wait themselves — e.g. `SttpOptions.readIdleTimeout` on the transport — or a dead
    * connection whose close frame never arrives can park the loop indefinitely.
    */
  def monitor(session: ObsSession, report: String => Unit): Either[ObsError, Unit] =
    session
      .withEvents(eventTypes = watchedEvents): sub =>
        snapshot(session = session, report = report)
        var running = true
        var failure = Option.empty[ObsError]
        while running do
          sub.next() match
            case Next.Item(event) =>
              describe(event = event).foreach(report)
              event match
                case _: ExitStarted                                                           => running = false
                case stream: StreamStateChanged if snapshotWorthy(state = stream.outputState) =>
                  snapshot(session = session, report = report)
                case _ => ()
            case Next.Failed(error) =>
              failure = Some(error)
              running = false
            case Next.Ended => running = false
        failure.toLeft(right = ())
      .flatten

  /** A long-lived monitor needs liveness detection: without `readIdleTimeout`, a dead connection whose close frame
    * never arrives (crash, NAT timeout, half-open TCP) stalls the event loop forever. The default here fails any
    * receive idle for 30 seconds, surfacing a retryable `ObsError.Timeout` the caller can feed to a reconnect policy.
    */
  def run(
    config:  ObsConfig,
    report:  String => Unit,
    options: SttpOptions = SttpOptions(readIdleTimeout = Some(30.seconds)),
  ): Either[ObsError, Unit] =
    SttpObsClient
      .connect(config = config, options = options)(use = session => monitor(session = session, report = report))
      .flatten

  def configuration(args: Array[String], environment: Map[String, String]): ObsConfig =
    ObsConfig(
      uri = args.headOption.getOrElse("ws://localhost:4455"),
      // A blank password (e.g. an empty OBS_WS_PASSWORD) means no authentication, matching ObsSettings.
      passwordProvider = PasswordProvider.fixed(value = environment.get("OBS_WS_PASSWORD").filter(_.trim.nonEmpty)),
    )

  /** Execute the CLI without terminating its process, so embedders and tests can inspect the status. */
  private[examples] def execute(args: Array[String], environment: Map[String, String]): Int =
    run(config = configuration(args = args, environment = environment), report = println) match
      case Right(())   => 0
      case Left(error) =>
        Console.err.println(s"OBS monitor failed: $error")
        1

  def main(args: Array[String]): Unit =
    val status = execute(args = args, environment = sys.env)
    if status != 0 then sys.exit(status)
