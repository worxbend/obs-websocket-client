package com.worxbend.obs.websocket.client

import com.worxbend.obs.websocket.client.protocol.*
import munit.FunSuite
import ox.channels.Channel
import scala.concurrent.duration.*
import java.util.concurrent.atomic.AtomicLong

class ObservationSuite extends FunSuite:
  private def event(index: Int): Event =
    UnknownEvent("Telemetry", JsonObject(Map("index" -> JsonValue.Num(BigDecimal(index)))))

  test("diagnostic outcomes omit server comments and preserve rejection codes"):
    assertEquals(DiagnosticOutcome.from(Right(())), DiagnosticOutcome.Succeeded)
    val error = ObsError.RequestRejected("Read", "id", 207, Some("secret"))
    assertEquals(DiagnosticOutcome.from(Left(error)), DiagnosticOutcome.Rejected(207))
    assert(!DiagnosticOutcome.from(Left(error)).toString.contains("secret"))
    assertEquals(DiagnosticOutcome.from(Left(ObsError.Closed)), DiagnosticOutcome.Failed)

  test("diagnostic registry drops newest on overflow and retains terminal loss counts"):
    val channel = Channel.buffered[SessionDiagnostic](1)
    val diagnostic = SessionDiagnostic.StateChanged(ConnectionState.Ready)
    val registry = DiagnosticRegistry(Map("id" -> (channel -> 0L)))
    val updated = registry.publish(diagnostic).publish(diagnostic)
    assertEquals(updated.entries("id")._2, 1L)
    channel.done()
    assertEquals(updated.publish(diagnostic).entries("id")._2, 1L)
    updated.close()

  test("diagnostic next and flow expose terminal completion"):
    val channel = Channel.buffered[SessionDiagnostic](1)
    val diagnostic = SessionDiagnostic.StateChanged(ConnectionState.Ready)
    channel.send(diagnostic)
    channel.done()
    val subscription = new ObsDiagnosticsSubscription(channel, () => 3L)
    assertEquals(subscription.flow.runToList(), List(Right(diagnostic)))
    assertEquals(subscription.droppedDiagnostics, 3L)

  test("typed selectors filter on caller and terminal error terminates flow"):
    val channel = Channel.buffered[Event](2)
    channel.send(event(1))
    channel.send(event(2))
    channel.done()
    val selector =
      EventSelector[Event]("Telemetry")(value => if value.eventData.int("index").contains(2) then Some(value) else None)
    val subscription = new TypedObsSubscription(new ObsSubscription(channel, () => 4L), selector)
    assertEquals(subscription.flow.runToList(), List(Right(event(2))))
    assertEquals(subscription.droppedEvents, 4L)

  test("coalescing bounds key cardinality and accounts overwritten and evicted values"):
    val window = EventWindow[String]()
      .add("one", event(1), 2)
      .add("two", event(2), 2)
      .add("one", event(3), 2)
      .add("three", event(4), 2)
    assertEquals(window.values, Vector("one" -> event(3), "three" -> event(4)))
    assertEquals(window.coalesced, 1L)
    assertEquals(window.evicted, 1L)

  test("sampled subscriptions share terminal and loss accounting across next and flow"):
    val source = new ObsSubscription(Channel.buffered[Event](1), () => 5L)
    val output = Channel.buffered[EventWindow[String]](1)
    val sampled = new SampledSubscription(output, new AtomicLong(7L), source)
    output.send(EventWindow(Vector("key" -> event(1))))
    output.done()
    assertEquals(sampled.flow.runToList(), List(Right(EventWindow(Vector("key" -> event(1))))))
    assertEquals(sampled.droppedWindows, 7L)
    assertEquals(sampled.droppedEvents, 5L)
    val failed = Channel.buffered[EventWindow[String]](1)
    failed.error(new SessionTerminated(ObsError.Overflow("test")))
    assertEquals(new SampledSubscription(failed, new AtomicLong(0L), source).next(), Left(ObsError.Overflow("test")))

  test("latest-value sampling rejects invalid bounds and preserves source errors"):
    val source = Channel.buffered[Event](2)
    val subscription = new ObsSubscription(source, () => 0L)
    assert(subscription.withLatestBy(Duration.Zero, 1)(_.eventType)(_ => ()).isLeft)
    assert(subscription.withLatestBy(1.second, 0)(_.eventType)(_ => ()).isLeft)
    source.error(new SessionTerminated(ObsError.Overflow("source")))
    assertEquals(
      subscription.withLatestBy(1.second, 2)(_.eventType)(_.next()),
      Right(Left(ObsError.Overflow("source")))
    )

  test("latest-value sampling emits a bounded window within a scope"):
    val source = Channel.buffered[Event](3)
    source.send(event(1))
    source.send(event(2))
    val subscription = new ObsSubscription(source, () => 0L)
    val result = subscription.withLatestBy(20.millis, 1)(_.eventType): sampled =>
      val window = sampled.next().toOption.get
      assertEquals(window.values, Vector("Telemetry" -> event(2)))
      assertEquals(window.coalesced, 1L)
    assertEquals(result, Right(()))

  test("sampling replaces an unread window and counts that loss deterministically"):
    val source = Channel.buffered[Event](2)
    source.send(event(1))
    source.send(event(2))
    val published = Channel.buffered[Unit](1)
    val clockIndex = new java.util.concurrent.atomic.AtomicInteger(0)
    val times = Vector(0L, 0L, 1000000000L, 1000000000L, 1000000000L, 2000000000L)
    val clock = () =>
      val index = clockIndex.getAndIncrement()
      if index == times.size then published.send(())
      times.lift(index).getOrElse(2000000000L)
    val subscription = new ObsSubscription(source, () => 0L, clock)
    val result = subscription.withLatestBy(1.second, 1)(_.eventType): sampled =>
      published.receive()
      assertEquals(sampled.droppedWindows, 1L)
      assertEquals(sampled.next().toOption.get.values, Vector("Telemetry" -> event(2)))
    assertEquals(result, Right(()))

  test("readiness delay rejects negative values"):
    assert(ReadinessPolicy(2, (-1).millis).validate.isLeft)

  test("raw subscription flow emits queued events and completes cleanly"):
    val channel = Channel.buffered[Event](1)
    channel.send(event(1))
    channel.done()
    assertEquals(new ObsSubscription(channel, () => 0L).flow.runToList(), List(Right(event(1))))

  test("raw subscription flow emits the concrete terminal failure once"):
    val channel = Channel.buffered[Event](1)
    channel.error(new SessionTerminated(ObsError.Overflow("event subscription")))
    assertEquals(
      new ObsSubscription(channel, () => 0L).flow.runToList(),
      List(Left(ObsError.Overflow("event subscription")))
    )

  test("typed subscription flow emits concrete failure once"):
    val channel = Channel.buffered[Event](1)
    channel.error(new SessionTerminated(ObsError.Overflow("typed")))
    val selector = EventSelector[Event]("Telemetry")(Some(_))
    val subscription = new TypedObsSubscription(new ObsSubscription(channel, () => 0L), selector)
    assertEquals(subscription.flow.runToList(), List(Left(ObsError.Overflow("typed"))))

  test("sampled subscription flow emits concrete failure once"):
    val source = new ObsSubscription(Channel.buffered[Event](1), () => 0L)
    val output = Channel.buffered[EventWindow[String]](1)
    output.error(new SessionTerminated(ObsError.Overflow("sampled")))
    val subscription = new SampledSubscription(output, new AtomicLong(0L), source)
    assertEquals(subscription.flow.runToList(), List(Left(ObsError.Overflow("sampled"))))

  test("diagnostic subscription flow emits concrete failure once"):
    val channel = Channel.buffered[SessionDiagnostic](1)
    channel.error(new SessionTerminated(ObsError.Overflow("diagnostics")))
    assertEquals(
      new ObsDiagnosticsSubscription(channel, () => 0L).flow.runToList(),
      List(Left(ObsError.Overflow("diagnostics")))
    )
