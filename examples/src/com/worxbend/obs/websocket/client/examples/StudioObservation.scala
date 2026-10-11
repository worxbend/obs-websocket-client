package com.worxbend.obs.websocket.client.examples

import com.worxbend.obs.websocket.client.{Next, ObsError}
import com.worxbend.obs.websocket.client.util.Defect
import com.worxbend.obs.websocket.client.protocol.Event
import com.worxbend.obs.websocket.client.protocol.events.{ExitStarted, StreamStateChanged}
import java.util.concurrent.atomic.AtomicReference
import ox.*
import ox.channels.{Channel, ChannelClosed}
import scala.util.control.NonFatal

/** Example-local observation policy, not a second session API. The control reader never runs requests or user
  * reporting. A single scoped worker owns both; pending state lines and snapshot triggers are bounded/coalesced.
  */
private[examples] object StudioObservation:
  enum SnapshotKind:
    case Baseline, Transition, Recovery

  trait Source:
    def withEvents[A](use: (() => Next[Event]) => A): Either[ObsError, A]
    def snapshot(kind: SnapshotKind): Either[ObsError, Vector[String]]

  private enum Phase:
    case Refreshing, Observing

  private case class Pending(
    events:          Vector[Event] = Vector.empty,
    coalesced:       Long = 0L,
    healthRequested: Boolean = false,
    phase:           Phase,
    goodbye:         Option[String] = None,
  ):
    def add(event: Event, capacity: Int): Pending =
      // A state change can follow the stream read while the recording read is still pending.
      // Keep one invalidation, not a replayable history, across the multi-request refresh.
      if phase == Phase.Refreshing then copy(healthRequested = true)
      else
        val requestHealth = healthRequested ||
          (event match
            case stream: StreamStateChanged => StudioMonitor.snapshotWorthy(stream.outputState)
            case _                          => false)
        if events.size < capacity then copy(events = events :+ event, healthRequested = requestHealth)
        else
          // Replace the oldest state of the same kind, retaining the chronological order of retained lines.
          // The sticky health trigger survives replacement of the event that originally requested it.
          val index = events.indexWhere(_.eventType == event.eventType)
          val kept  = if index >= 0 then events.patch(index, Nil, 1) else events.tail
          copy(events = kept :+ event, coalesced = coalesced + 1, healthRequested = requestHealth)

  private class Mailbox(capacity: Int, recovery: Boolean):
    private val pending = new AtomicReference(Pending(phase = if recovery then Phase.Refreshing else Phase.Observing))
    private val wake    = Channel.buffered[Unit](1)

    def offer(event: Event): Unit =
      pending.updateAndGet(_.add(event, capacity)).discard
      wake.trySendOrClosed(()).discard

    def refreshed(): Unit =
      // Discard replayable state but retain invalidation from events during these non-atomic reads.
      // A coalesced follow-up reconciles changes that arrived after one of the snapshot responses.
      pending.updateAndGet(_.copy(
        events    = Vector.empty,
        coalesced = 0L,
        phase     = Phase.Observing,
      )).discard

    def finish(goodbye: Option[String]): Unit =
      pending.updateAndGet(_.copy(goodbye = goodbye)).discard
      wake.doneOrClosed().discard

    def take(): Option[Pending] = wake.receiveOrClosed() match
      case _: ChannelClosed => None
      case () => Some(pending.getAndUpdate(_.copy(events = Vector.empty, coalesced = 0L, healthRequested = false)))

    def goodbye: Option[String] = pending.get().goodbye

  /** The recovery budget is cumulative for this monitor invocation: repeated overflow cannot become an endless
    * resubscribe/request loop. No time-based application refresh or retry task is installed.
    */
  def run(source: Source, report: String => Unit, options: StudioMonitor.MonitorOptions): Either[ObsError, Unit] =
    options.validate.flatMap: _ =>
      try
        var recoveries = 0
        var result     = Option.empty[Either[ObsError, Unit]]
        while result.isEmpty do
          val attempt = source.withEvents: read =>
            observe(read, source, report, options, recoveries)
          attempt.flatten match
            case Left(ObsError.Overflow("event subscription")) if recoveries < options.maxOverflowRecoveries =>
              recoveries += 1
            case terminal => result = Some(terminal)
        result.get
      catch
        case NonFatal(cause) =>
          Left(ObsError.InternalError(message = s"Studio monitor worker failed: ${Defect.describe(cause = cause)}"))

  private def observe(
    read:       () => Next[Event],
    source:     Source,
    report:     String => Unit,
    options:    StudioMonitor.MonitorOptions,
    recoveries: Int,
  ): Either[ObsError, Unit] = supervised:
    val mailbox  = new Mailbox(options.pendingCapacity, recovery = recoveries > 0)
    val terminal = Channel.buffered[Next[Event]](2)
    val worker   = fork:
      val result = work(source, report, mailbox, recoveries)
      result.left.foreach(error => terminal.send(Next.Failed(error)))
      result
    forkDiscard:
      terminal.send(drain(read, mailbox))
    terminal.receive() match
      case Next.Failed(error) => Left(error) // scope cancellation discards this generation's pending work
      case end                =>
        val goodbye = end match
          case Next.Item(_) => Some("obs is shutting down")
          case _            => None
        mailbox.finish(goodbye)
        timeoutOption(options.shutdownTimeout)(worker.join())
          .getOrElse(Left(ObsError.Timeout(operation = "studio monitor shutdown")))

  private def drain(read: () => Next[Event], mailbox: Mailbox): Next[Event] =
    var terminal = Option.empty[Next[Event]]
    while terminal.isEmpty do
      read() match
        case item @ Next.Item(_: ExitStarted) => terminal = Some(item)
        case Next.Item(event)                 => mailbox.offer(event)
        case end                              => terminal = Some(end)
    terminal.get

  private def work(source: Source, report: String => Unit, mailbox: Mailbox, recoveries: Int): Either[ObsError, Unit] =
    val kind = if recoveries > 0 then SnapshotKind.Recovery else SnapshotKind.Baseline
    if recoveries > 0 then report(s"monitor event overflow: authoritative refresh (recovery $recoveries)")
    source.snapshot(kind) match
      case Left(error) if kind == SnapshotKind.Recovery => Left(error)
      case initial                                      =>
        if kind == SnapshotKind.Recovery then mailbox.refreshed()
        reportSnapshot(initial, report)
        var running = true
        var failure = Option.empty[ObsError]
        while running do
          mailbox.take() match
            case None        => running = false
            case Some(batch) =>
              reportBatch(batch, source, report, authoritative = recoveries > 0) match
                case Right(())   => ()
                case Left(error) =>
                  failure = Some(error)
                  running = false
        if failure.isEmpty then mailbox.goodbye.foreach(report)
        failure.toLeft(())

  private def reportBatch(
    batch:         Pending,
    source:        Source,
    report:        String => Unit,
    authoritative: Boolean,
  ): Either[ObsError, Unit] =
    if batch.coalesced > 0 then report(s"monitor coalesced ${batch.coalesced} pending state events")
    if authoritative && (batch.events.nonEmpty || batch.healthRequested) then
      // OBS has no event/snapshot revision boundary. After a gap, even an event still buffered in the new
      // subscription may predate the refresh. Treat it only as invalidation, never fold/report it as current state.
      source.snapshot(SnapshotKind.Recovery).map(_.foreach(report))
    else
      batch.events.foreach(event => StudioMonitor.describe(event).foreach(report))
      if batch.healthRequested then reportSnapshot(source.snapshot(SnapshotKind.Transition), report)
      Right(())

  private def reportSnapshot(snapshot: Either[ObsError, Vector[String]], report: String => Unit): Unit = snapshot match
    case Right(lines) => lines.foreach(report)
    case Left(error)  => report(s"stream status unavailable: $error")
