package com.worxbend.obs.websocket.client.examples

import com.worxbend.obs.websocket.client.{Next, ObsError}
import com.worxbend.obs.websocket.client.protocol.Event
import com.worxbend.obs.websocket.client.protocol.events.{ExitStarted, RecordStateChanged, StreamStateChanged}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import munit.FunSuite
import ox.*
import ox.channels.Channel
import scala.collection.mutable
import scala.concurrent.duration.*

class StudioObservationSuite extends FunSuite:
  private val overflow                           = ObsError.Overflow(resource = "event subscription")
  private val exit                               = Next.Item(ExitStarted())
  private def await(latch: CountDownLatch): Unit =
    // Do not evaluate a blocking wait inside munit.assert: clue capture locks the suite across the wait.
    if !latch.await(5, TimeUnit.SECONDS) then throw new IllegalStateException("test barrier timed out")
  private def stream(index: Int): StreamStateChanged = StreamStateChanged(true, s"state-$index")

  test("a slow reporter cannot block control drain; pending lines and snapshot triggers coalesce"):
    timeout(5.seconds):
      supervised:
        val reporting = new CountDownLatch(1)
        val drained   = new CountDownLatch(1)
        val release   = Channel.rendezvous[Unit]
        val snapshots = new AtomicInteger(0)
        val lines     = mutable.Buffer.empty[String]
        val source    = new StudioObservation.Source:
          def withEvents[A](use: (() => Next[Event]) => A): Either[ObsError, A] =
            var index = 0
            Right(use(() =>
              await(reporting)
              if index < 100 then
                val event =
                  if index == 0 then StreamStateChanged(true, StudioMonitor.StreamReconnectingState) else stream(index)
                index += 1
                Next.Item(event)
              else
                drained.countDown()
                exit
            ))
          def snapshot(kind: StudioObservation.SnapshotKind): Either[ObsError, Vector[String]] =
            snapshots.incrementAndGet().discard
            Right(Vector(s"snapshot: $kind"))
        val monitor = fork:
          StudioObservation.run(
            source,
            line =>
              lines += line
              if line == "snapshot: Baseline" then
                reporting.countDown()
                release.receive()
            ,
            StudioMonitor.MonitorOptions(pendingCapacity = 2, shutdownTimeout = 3.seconds),
          )
        await(drained) // all events AND ExitStarted are read while the reporter is still blocked
        release.send(())
        assertEquals(monitor.join(), Right(()))
        assertEquals(snapshots.get(), 2)
        assertEquals(
          lines.toVector,
          Vector(
            "snapshot: Baseline",
            "monitor coalesced 98 pending state events",
            "stream: state-98 (active=true)",
            "stream: state-99 (active=true)",
            "snapshot: Transition",
            "obs is shutting down",
          ),
        )

  test("a full mailbox admits a new state kind by evicting its oldest pending line"):
    timeout(5.seconds):
      supervised:
        val started = new CountDownLatch(1)
        val drained = new CountDownLatch(1)
        val release = Channel.rendezvous[Unit]
        val lines   = mutable.Buffer.empty[String]
        val source  = new StudioObservation.Source:
          def withEvents[A](use: (() => Next[Event]) => A): Either[ObsError, A] =
            val events = Vector(stream(1), stream(2), RecordStateChanged(false, "stopped", None)).iterator
            Right(use(() =>
              await(started)
              if events.hasNext then Next.Item(events.next())
              else
                drained.countDown()
                exit
            ))
          def snapshot(kind: StudioObservation.SnapshotKind): Either[ObsError, Vector[String]] =
            started.countDown()
            release.receive()
            Right(Vector.empty)
        val monitor = fork:
          StudioObservation.run(source, line => lines += line, StudioMonitor.MonitorOptions(pendingCapacity = 2))
        await(drained)
        release.send(())
        assertEquals(monitor.join(), Right(()))
        assertEquals(
          lines.toVector,
          Vector(
            "monitor coalesced 1 pending state events",
            "stream: state-2 (active=true)",
            "record: stopped (active=false)",
            "obs is shutting down",
          ),
        )

  test("a stalled baseline snapshot drains a transition burst with only one coalesced follow-up request"):
    timeout(5.seconds):
      supervised:
        val started = new CountDownLatch(1)
        val drained = new CountDownLatch(1)
        val release = Channel.rendezvous[Unit]
        val calls   = new AtomicInteger(0)
        val source  = new StudioObservation.Source:
          def withEvents[A](use: (() => Next[Event]) => A): Either[ObsError, A] =
            var index = 0
            Right(use(() =>
              await(started)
              if index < 100 then
                index += 1
                Next.Item(StreamStateChanged(true, StudioMonitor.StreamReconnectingState))
              else
                drained.countDown()
                exit
            ))
          def snapshot(kind: StudioObservation.SnapshotKind): Either[ObsError, Vector[String]] =
            calls.incrementAndGet().discard
            if kind == StudioObservation.SnapshotKind.Baseline then
              started.countDown()
              release.receive()
            Right(Vector.empty)
        val monitor = fork:
          StudioObservation.run(
            source,
            _ => (),
            StudioMonitor.MonitorOptions(pendingCapacity = 2, shutdownTimeout = 3.seconds),
          )
        await(drained)
        assertEquals(calls.get(), 1) // requests did not fork per event while the baseline was stalled
        release.send(())
        assertEquals(monitor.join(), Right(()))
        assertEquals(calls.get(), 2)

  test("ExitStarted bounds cancellation of a stalled snapshot instead of waiting for the request budget"):
    timeout(5.seconds):
      val started = new CountDownLatch(1)
      val blocked = Channel.rendezvous[Unit]
      val source  = new StudioObservation.Source:
        def withEvents[A](use: (() => Next[Event]) => A): Either[ObsError, A] =
          Right(use(() =>
            await(started)
            exit
          ))
        def snapshot(kind: StudioObservation.SnapshotKind): Either[ObsError, Vector[String]] =
          started.countDown()
          blocked.receive()
          Right(Vector.empty)
      assertEquals(
        StudioObservation.run(source, _ => (), StudioMonitor.MonitorOptions(shutdownTimeout = 100.millis)),
        Left(ObsError.Timeout(operation = "studio monitor shutdown")),
      )

  test("overflow recreates the subscription; buffered state only invalidates authoritative snapshots"):
    timeout(5.seconds):
      val subscriptions         = new AtomicInteger(0)
      val releases              = new AtomicInteger(0)
      val baselineStarted       = new CountDownLatch(1)
      val recoveryStarted       = new CountDownLatch(1)
      val staleDrained          = new CountDownLatch(1)
      val authoritativeReported = new CountDownLatch(2)
      val blocked               = Channel.rendezvous[Unit]
      val lines                 = mutable.Buffer.empty[String]
      val source                = new StudioObservation.Source:
        def withEvents[A](use: (() => Next[Event]) => A): Either[ObsError, A] =
          val generation = subscriptions.getAndIncrement()
          var index      = 0
          try Right(use(() =>
              if generation == 0 then
                await(baselineStarted)
                Next.Failed(overflow)
              else if index == 0 then
                await(recoveryStarted)
                index += 1
                Next.Item(stream(1))
              else if index == 1 then
                staleDrained.countDown()
                await(authoritativeReported)
                index += 1
                Next.Item(stream(2))
              else exit
            ))
          finally releases.incrementAndGet().discard
        def snapshot(kind: StudioObservation.SnapshotKind): Either[ObsError, Vector[String]] = kind match
          case StudioObservation.SnapshotKind.Baseline =>
            baselineStarted.countDown()
            blocked.receive()
            Right(Vector("stale baseline"))
          case StudioObservation.SnapshotKind.Recovery =>
            recoveryStarted.countDown()
            await(staleDrained)
            Right(Vector("authoritative stream and record"))
          case StudioObservation.SnapshotKind.Transition => fail("stale event must not trigger another snapshot")
      val result = StudioObservation.run(
        source,
        line =>
          lines += line
          if line == "authoritative stream and record" then authoritativeReported.countDown()
        ,
        StudioMonitor.MonitorOptions(),
      )
      assertEquals(result, Right(()))
      assertEquals(subscriptions.get(), 2)
      assertEquals(releases.get(), 2)
      assertEquals(
        lines.toVector,
        Vector(
          "monitor event overflow: authoritative refresh (recovery 1)",
          "authoritative stream and record",
          "authoritative stream and record",
          "authoritative stream and record",
          "obs is shutting down",
        ),
      )

  test("an event between recovery reads schedules a coalesced authoritative follow-up"):
    timeout(5.seconds):
      val subscriptions   = new AtomicInteger(0)
      val snapshots       = new AtomicInteger(0)
      val baselineStarted = new CountDownLatch(1)
      val streamRead      = new CountDownLatch(1)
      val eventDrained    = new CountDownLatch(1)
      val freshReported   = new CountDownLatch(1)
      val blocked         = Channel.rendezvous[Unit]
      val lines           = mutable.Buffer.empty[String]
      val source          = new StudioObservation.Source:
        def withEvents[A](use: (() => Next[Event]) => A): Either[ObsError, A] =
          val generation = subscriptions.getAndIncrement()
          var index      = 0
          Right(use(() =>
            if generation == 0 then
              await(baselineStarted)
              Next.Failed(overflow)
            else if index == 0 then
              await(streamRead)
              index += 1
              Next.Item(stream(1))
            else
              // This read occurs only after offer has completed for the previous item.
              eventDrained.countDown()
              await(freshReported)
              exit
          ))
        def snapshot(kind: StudioObservation.SnapshotKind): Either[ObsError, Vector[String]] =
          if kind == StudioObservation.SnapshotKind.Baseline then
            baselineStarted.countDown()
            blocked.receive()
            Right(Vector.empty)
          else if snapshots.getAndIncrement() == 0 then
            streamRead.countDown()
            await(eventDrained)
            Right(Vector("older stream snapshot; recording read finished"))
          else Right(Vector("fresh authoritative stream and recording"))
      val result = StudioObservation.run(
        source,
        line =>
          lines += line
          if line == "fresh authoritative stream and recording" then freshReported.countDown()
        ,
        StudioMonitor.MonitorOptions(),
      )
      assertEquals(result, Right(()))
      assertEquals(snapshots.get(), 2)
      assertEquals(
        lines.toVector,
        Vector(
          "monitor event overflow: authoritative refresh (recovery 1)",
          "older stream snapshot; recording read finished",
          "fresh authoritative stream and recording",
          "obs is shutting down",
        ),
      )

  test("persistent subscription overload exhausts a cumulative recovery budget"):
    timeout(5.seconds):
      val subscriptions = new AtomicInteger(0)
      val ready         = Channel.rendezvous[Unit]
      val blocked       = Channel.rendezvous[Unit]
      val source        = new StudioObservation.Source:
        def withEvents[A](use: (() => Next[Event]) => A): Either[ObsError, A] =
          subscriptions.incrementAndGet().discard
          Right(use(() =>
            ready.receive()
            Next.Failed(overflow)
          ))
        def snapshot(kind: StudioObservation.SnapshotKind): Either[ObsError, Vector[String]] =
          ready.send(())
          blocked.receive()
          Right(Vector.empty)
      assertEquals(
        StudioObservation.run(source, _ => (), StudioMonitor.MonitorOptions(maxOverflowRecoveries = 2)),
        Left(overflow),
      )
      assertEquals(subscriptions.get(), 3)

  test("failed authoritative recovery terminates even while the new control reader waits"):
    timeout(5.seconds):
      val subscriptions = new AtomicInteger(0)
      val started       = Channel.rendezvous[Unit]
      val blocked       = Channel.rendezvous[Unit]
      val failure       = ObsError.UnsupportedRequest(requestType = "GetRecordStatus")
      val source        = new StudioObservation.Source:
        def withEvents[A](use: (() => Next[Event]) => A): Either[ObsError, A] =
          val generation = subscriptions.getAndIncrement()
          Right(use(() =>
            if generation == 0 then
              started.receive()
              Next.Failed(overflow)
            else
              blocked.receive()
              Next.Ended
          ))
        def snapshot(kind: StudioObservation.SnapshotKind): Either[ObsError, Vector[String]] =
          if kind == StudioObservation.SnapshotKind.Baseline then
            started.send(())
            blocked.receive()
            Right(Vector.empty)
          else Left(failure)
      assertEquals(StudioObservation.run(source, _ => (), StudioMonitor.MonitorOptions()), Left(failure))
      assertEquals(subscriptions.get(), 2)

  test("failed follow-up recovery terminates without treating stale state as usable"):
    timeout(5.seconds):
      val subscriptions    = new AtomicInteger(0)
      val snapshots        = new AtomicInteger(0)
      val baselineStarted  = new CountDownLatch(1)
      val recoveryReported = new CountDownLatch(1)
      val blocked          = Channel.rendezvous[Unit]
      val failure          = ObsError.UnsupportedRequest(requestType = "GetRecordStatus")
      val source           = new StudioObservation.Source:
        def withEvents[A](use: (() => Next[Event]) => A): Either[ObsError, A] =
          val generation = subscriptions.getAndIncrement()
          var first      = true
          Right(use(() =>
            if generation == 0 then
              await(baselineStarted)
              Next.Failed(overflow)
            else if first then
              await(recoveryReported)
              first = false
              Next.Item(stream(1))
            else
              blocked.receive()
              Next.Ended
          ))
        def snapshot(kind: StudioObservation.SnapshotKind): Either[ObsError, Vector[String]] =
          if kind == StudioObservation.SnapshotKind.Baseline then
            baselineStarted.countDown()
            blocked.receive()
            Right(Vector.empty)
          else if snapshots.getAndIncrement() == 0 then Right(Vector("initial recovery"))
          else Left(failure)
      val result = StudioObservation.run(
        source,
        line =>
          if line == "initial recovery" then recoveryReported.countDown(),
        StudioMonitor.MonitorOptions(),
      )
      assertEquals(result, Left(failure))
      assertEquals(snapshots.get(), 2)
      assertEquals(subscriptions.get(), 2)

  test("transport failure is terminal rather than a local overflow recovery"):
    timeout(5.seconds):
      val subscriptions = new AtomicInteger(0)
      val started       = Channel.rendezvous[Unit]
      val blocked       = Channel.rendezvous[Unit]
      val failure       = ObsError.Transport(message = "peer closed", closeCode = Some(1000))
      val source        = new StudioObservation.Source:
        def withEvents[A](use: (() => Next[Event]) => A): Either[ObsError, A] =
          subscriptions.incrementAndGet().discard
          Right(use(() =>
            started.receive()
            Next.Failed(failure)
          ))
        def snapshot(kind: StudioObservation.SnapshotKind): Either[ObsError, Vector[String]] =
          started.send(())
          blocked.receive()
          Right(Vector.empty)
      assertEquals(StudioObservation.run(source, _ => (), StudioMonitor.MonitorOptions()), Left(failure))
      assertEquals(subscriptions.get(), 1)

  test("report defects cancel the control reader and surface as an internal failure"):
    timeout(5.seconds):
      val blocked = Channel.rendezvous[Unit]
      val source  = new StudioObservation.Source:
        def withEvents[A](use: (() => Next[Event]) => A): Either[ObsError, A] = Right(use(() =>
          blocked.receive()
          Next.Ended
        ))
        def snapshot(kind: StudioObservation.SnapshotKind): Either[ObsError, Vector[String]] = Right(Vector("baseline"))
      val result = StudioObservation.run(
        source,
        _ => throw new IllegalStateException("report defect"),
        StudioMonitor.MonitorOptions(),
      )
      assert(result.left.toOption.exists(_.isInstanceOf[ObsError.InternalError]), result)

  test("invalid worker policy fails before creating a subscription"):
    val source = new StudioObservation.Source:
      def withEvents[A](use: (() => Next[Event]) => A): Either[ObsError, A] = fail("must not subscribe")
      def snapshot(kind: StudioObservation.SnapshotKind): Either[ObsError, Vector[String]] = fail("must not refresh")
    for options <- Vector(
                     StudioMonitor.MonitorOptions(pendingCapacity       = 1),
                     StudioMonitor.MonitorOptions(maxOverflowRecoveries = -1),
                     StudioMonitor.MonitorOptions(shutdownTimeout       = Duration.Zero),
                   )
    do
      val result = StudioObservation.run(source, _ => (), options)
      assert(result.left.toOption.exists(_.isInstanceOf[ObsError.InvalidConfiguration]), result)
