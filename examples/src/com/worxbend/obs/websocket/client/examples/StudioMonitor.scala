package com.worxbend.obs.websocket.client.examples

import com.worxbend.obs.websocket.client.protocol.Event
import com.worxbend.obs.websocket.client.protocol.events.{ExitStarted, RecordStateChanged, StreamStateChanged}
import com.worxbend.obs.websocket.client.protocol.requests.{GetRecordStatus, GetStreamStatus, GetStreamStatusResponse}
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
    * surfaces in the rendered line instead of being silently corrected. Counters render with `toPlainString`
    * because the wire parser preserves exponent form, which `BigDecimal.toString` would print as `1E+6`.
    */
  def describeStatus(status: GetStreamStatusResponse): String =
    val dropped = status.outputSkippedFrames
    val total   = status.outputTotalFrames
    val ratio   =
      if total > 0 then (dropped / total * 100).setScale(2, BigDecimal.RoundingMode.HALF_UP) else BigDecimal(0)
    s"stream health: skipped ${dropped.bigDecimal.toPlainString} of ${total.bigDecimal.toPlainString} frames ($ratio%), " +
      s"congestion ${status.outputCongestion.bigDecimal.toPlainString}, ${status.outputBytes.bigDecimal.toPlainString} bytes sent"

  /** Pending event lines are bounded; a full worker mailbox replaces older state lines and reports the loss.
    * Snapshot triggers coalesce per drained batch, with at most one request in flight. Recovery is a cumulative
    * lifetime budget, not an unbounded retry loop. Shutdown bounds cooperative request/report cancellation.
    */
  final case class MonitorOptions(
    pendingCapacity:       Int = 32,
    maxOverflowRecoveries: Int = 3,
    shutdownTimeout:       FiniteDuration = 1.second,
  ):
    private[examples] def validate: Either[ObsError, Unit] =
      if pendingCapacity < 2 || maxOverflowRecoveries < 0 || shutdownTimeout <= Duration.Zero then
        Left(ObsError.InvalidConfiguration(message =
          "Monitor needs capacity >= 2, nonnegative recoveries, and positive shutdown deadline"
        ))
      else Right(())

  /** React until OBS exits or the session ends. The fast control reader is independent of snapshot latency and
    * reporting. A baseline, meaningful stream transitions, and post-loss state invalidations cause reads.
    * Overflow recreates the subscription and refreshes stream AND recording state; recovery failure is terminal.
    * Old-generation work is discarded. State events drained during recovery retain a coalesced invalidation,
    * not replayable historical state. In a recovered generation, later state events are also only
    * invalidations for coalesced authoritative reads: OBS provides no event/snapshot ordering boundary.
    * OBS also does not provide an atomic cross-output snapshot/revision.
    */
  def monitor(
    session:        ObsSession,
    report:         String => Unit,
    monitorOptions: MonitorOptions = MonitorOptions(),
  ): Either[ObsError, Unit] =
    StudioObservation.run(observationSource(session), report, monitorOptions)

  private[examples] def observationSource(session: ObsSession): StudioObservation.Source =
    new StudioObservation.Source:
      def withEvents[A](use: (() => Next[Event]) => A): Either[ObsError, A] =
        session.withEvents(eventTypes = watchedEvents)(sub => use(() => sub.next()))

      def snapshot(kind: StudioObservation.SnapshotKind): Either[ObsError, Vector[String]] =
        session.request(request = GetStreamStatus()).flatMap: stream =>
          val health = describeStatus(stream)
          if kind != StudioObservation.SnapshotKind.Recovery then Right(Vector(health))
          else
            session.request(request = GetRecordStatus()).map: record =>
              Vector(
                s"stream snapshot: active=${stream.outputActive} (reconnecting=${stream.outputReconnecting})",
                health,
                s"record snapshot: active=${record.outputActive} (paused=${record.outputPaused})",
              )

  /** Healthy OBS sessions can be indefinitely quiet: lack of application events is not proof of transport death.
    * Default backend policy therefore has no read-idle deadline. An explicitly supplied `readIdleTimeout` still
    * fails silent receives (including HEALTHY quiet sessions); it is not a heartbeat. Half-open detection without
    * that tradeoff belongs to a backend's transport ping/pong policy, not timer-based application snapshots.
    */
  def run(
    config:         ObsConfig,
    report:         String => Unit,
    options:        SttpOptions = SttpOptions(),
    monitorOptions: MonitorOptions = MonitorOptions(),
  ): Either[ObsError, Unit] =
    SttpObsClient
      .connect(config = config, options = options)(use = session => monitor(session, report, monitorOptions))
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
