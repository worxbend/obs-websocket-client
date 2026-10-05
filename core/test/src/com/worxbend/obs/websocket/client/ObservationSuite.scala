package com.worxbend.obs.websocket.client

import com.worxbend.obs.websocket.client.protocol.*
import munit.FunSuite
import ox.channels.Channel
import scala.concurrent.duration.*
import java.util.concurrent.atomic.AtomicLong

class ObservationSuite extends FunSuite:
  private def event(index: Int): Event =
    UnknownEvent(
      eventType = "Telemetry",
      eventData = JsonObject(fields = Map("index" -> JsonValue.Num(value = BigDecimal(index)))),
    )

  test("diagnostic outcomes omit server comments and preserve rejection codes"):
    assertEquals(DiagnosticOutcome.from(result = Right(())), DiagnosticOutcome.Succeeded)
    val error = ObsError.RequestRejected(requestType = "Read", requestId = "id", code = 207, comment = Some("secret"))
    assertEquals(DiagnosticOutcome.from(result = Left(error)), DiagnosticOutcome.Rejected(code = 207))
    assert(!DiagnosticOutcome.from(result = Left(error)).toString.contains("secret"))
    assertEquals(DiagnosticOutcome.from(result = Left(ObsError.Closed)), DiagnosticOutcome.Failed)

  test("diagnostic registry drops newest on overflow and retains terminal loss counts"):
    val channel    = Channel.buffered[SessionDiagnostic](1)
    val diagnostic = SessionDiagnostic.StateChanged(state = ConnectionState.Ready)
    val registry   = DiagnosticRegistry(entries = Map("id" -> (channel -> 0L)))
    val updated    = registry.publish(event = diagnostic).publish(event = diagnostic)
    assertEquals(updated.entries("id")._2, 1L)
    channel.done()
    assertEquals(updated.publish(event = diagnostic).entries("id")._2, 1L)
    updated.close()

  test("diagnostic next and flow expose terminal completion"):
    val channel    = Channel.buffered[SessionDiagnostic](1)
    val diagnostic = SessionDiagnostic.StateChanged(state = ConnectionState.Ready)
    channel.send(diagnostic)
    channel.done()
    val subscription = new ObsDiagnosticsSubscription(channel = channel, () => 3L)
    assertEquals(subscription.flow.runToList(), List(Right(diagnostic)))
    assertEquals(subscription.droppedDiagnostics, 3L)

  test("typed selectors filter on caller and terminal error terminates flow"):
    val channel = Channel.buffered[Event](2)
    channel.send(event(index = 1))
    channel.send(event(index = 2))
    channel.done()
    val selector =
      EventSelector[Event]("Telemetry")(value =>
        if value.eventData.int(name = "index").contains(2) then Some(value) else None
      )
    val subscription = new TypedObsSubscription(new ObsSubscription(channel = channel, () => 4L), selector)
    assertEquals(subscription.flow.runToList(), List(Right(event(index = 2))))
    assertEquals(subscription.droppedEvents, 4L)

  test("coalescing bounds key cardinality and accounts overwritten and evicted values"):
    val window = EventWindow[String]()
      .add(key = "one", event = event(index = 1), maxKeys = 2)
      .add(key = "two", event = event(index = 2), maxKeys = 2)
      .add(key = "one", event = event(index = 3), maxKeys = 2)
      .add(key = "three", event = event(index = 4), maxKeys = 2)
    assertEquals(window.values, Vector("one" -> event(index = 3), "three" -> event(index = 4)))
    assertEquals(window.coalesced, 1L)
    assertEquals(window.evicted, 1L)

  test("sampled subscriptions share terminal and loss accounting across next and flow"):
    val source  = new ObsSubscription(channel = Channel.buffered[Event](1), () => 5L)
    val output  = Channel.buffered[EventWindow[String]](1)
    val sampled = new SampledSubscription(output, new AtomicLong(7L), source)
    output.send(EventWindow(values = Vector("key" -> event(index = 1))))
    output.done()
    assertEquals(sampled.flow.runToList(), List(Right(EventWindow(values = Vector("key" -> event(index = 1))))))
    assertEquals(sampled.droppedWindows, 7L)
    assertEquals(sampled.droppedEvents, 5L)
    val failed = Channel.buffered[EventWindow[String]](1)
    failed.error(new SessionTerminated(error = ObsError.Overflow(resource = "test")))
    assertEquals(
      new SampledSubscription(failed, new AtomicLong(0L), source).next(),
      Next.Failed(error = ObsError.Overflow(resource = "test")),
    )

  test("latest-value sampling rejects invalid bounds and preserves source errors"):
    val source       = Channel.buffered[Event](2)
    val subscription = new ObsSubscription(channel = source, () => 0L)
    assert(subscription.withLatestBy(interval = Duration.Zero, maxKeys = 1)(key = _.eventType)(_ => ()).isLeft)
    assert(subscription.withLatestBy(interval = 1.second, maxKeys = 0)(key = _.eventType)(_ => ()).isLeft)
    source.error(new SessionTerminated(error = ObsError.Overflow(resource = "source")))
    assertEquals(
      subscription.withLatestBy(interval = 1.second, maxKeys = 2)(key = _.eventType)(use = _.next()),
      Right(Next.Failed(error = ObsError.Overflow(resource = "source"))),
    )

  test("latest-value sampling completes cleanly when the source ends"):
    val source = Channel.buffered[Event](1)
    source.done()
    val subscription = new ObsSubscription(channel = source, () => 0L)
    assertEquals(
      subscription.withLatestBy(interval = 20.millis, maxKeys = 1)(key = _.eventType)(use = _.next()),
      Right(Next.Ended),
    )

  test("a throwing sampling key function terminates the stream with InternalError and preserves the scope"):
    val source = Channel.buffered[Event](2)
    source.send(event(index = 1))
    val subscription             = new ObsSubscription(channel = source, () => 0L)
    val failing: Event => String = _ => throw new IllegalStateException("key defect")
    val result = subscription.withLatestBy(interval = 1.second, maxKeys = 2)(key = failing): sampled =>
      sampled.flow.runToList()
    val expected: Either[ObsError, List[Either[ObsError, EventWindow[String]]]] =
      Right(List(Left(ObsError.InternalError(message = "Sampling key function failed: key defect"))))
    assertEquals(result, expected)

  test("a message-less sampling key defect falls back to the throwable class name"):
    val source = Channel.buffered[Event](2)
    source.send(event(index = 1))
    val subscription             = new ObsSubscription(channel = source, () => 0L)
    val failing: Event => String = _ => throw new IllegalStateException()
    val result = subscription.withLatestBy(interval = 1.second, maxKeys = 2)(key = failing): sampled =>
      sampled.flow.runToList()
    val expected: Either[ObsError, List[Either[ObsError, EventWindow[String]]]] =
      Right(List(Left(ObsError.InternalError(message = "Sampling key function failed: IllegalStateException"))))
    assertEquals(result, expected)

  test("latest-value sampling emits a bounded window within a scope"):
    val source = Channel.buffered[Event](3)
    source.send(event(index = 1))
    source.send(event(index = 2))
    val subscription = new ObsSubscription(channel = source, () => 0L)
    val result       = subscription.withLatestBy(interval = 20.millis, maxKeys = 1)(key = _.eventType): sampled =>
      val window = sampled.next() match
        case Next.Item(window) => window
        case other             => fail(s"Expected a window, got $other")
      assertEquals(window.values, Vector("Telemetry" -> event(index = 2)))
      assertEquals(window.coalesced, 1L)
    assertEquals(result, Right(()))

  test("sampling replaces an unread window and counts that loss deterministically"):
    val source = Channel.buffered[Event](2)
    source.send(event(index = 1))
    source.send(event(index = 2))
    val published  = Channel.buffered[Unit](1)
    val clockIndex = new java.util.concurrent.atomic.AtomicInteger(0)
    val times      = Vector(0L, 0L, 1000000000L, 1000000000L, 1000000000L, 2000000000L)
    val clock      = () =>
      val index = clockIndex.getAndIncrement()
      if index == times.size then published.send(())
      times.lift(index).getOrElse(2000000000L)
    val subscription = new ObsSubscription(channel = source, () => 0L, nanoTime = clock)
    val result       = subscription.withLatestBy(interval = 1.second, maxKeys = 1)(key = _.eventType): sampled =>
      published.receive()
      assertEquals(sampled.droppedWindows, 1L)
      sampled.next() match
        case Next.Item(window) => assertEquals(window.values, Vector("Telemetry" -> event(index = 2)))
        case other             => fail(s"Expected a window, got $other")
    assertEquals(result, Right(()))

  test("readiness delay rejects negative values"):
    assert(ReadinessPolicy(maxAttempts = 2, delay = (-1).millis).validate.isLeft)

  test("raw subscription flow emits queued events and completes cleanly"):
    val channel = Channel.buffered[Event](1)
    channel.send(event(index = 1))
    channel.done()
    assertEquals(new ObsSubscription(channel = channel, () => 0L).flow.runToList(), List(Right(event(index = 1))))

  test("raw subscription flow emits the concrete terminal failure once"):
    val channel = Channel.buffered[Event](1)
    channel.error(new SessionTerminated(error = ObsError.Overflow(resource = "event subscription")))
    assertEquals(
      new ObsSubscription(channel = channel, () => 0L).flow.runToList(),
      List(Left(ObsError.Overflow(resource = "event subscription"))),
    )

  test("typed subscription flow emits concrete failure once"):
    val channel = Channel.buffered[Event](1)
    channel.error(new SessionTerminated(error = ObsError.Overflow(resource = "typed")))
    val selector     = EventSelector[Event]("Telemetry")(Some(_))
    val subscription = new TypedObsSubscription(new ObsSubscription(channel = channel, () => 0L), selector)
    assertEquals(subscription.flow.runToList(), List(Left(ObsError.Overflow(resource = "typed"))))

  test("sampled subscription flow emits concrete failure once"):
    val source = new ObsSubscription(channel = Channel.buffered[Event](1), () => 0L)
    val output = Channel.buffered[EventWindow[String]](1)
    output.error(new SessionTerminated(error = ObsError.Overflow(resource = "sampled")))
    val subscription = new SampledSubscription(output, new AtomicLong(0L), source)
    assertEquals(subscription.flow.runToList(), List(Left(ObsError.Overflow(resource = "sampled"))))

  test("diagnostic subscription flow emits concrete failure once"):
    val channel = Channel.buffered[SessionDiagnostic](1)
    channel.error(new SessionTerminated(error = ObsError.Overflow(resource = "diagnostics")))
    assertEquals(
      new ObsDiagnosticsSubscription(channel = channel, () => 0L).flow.runToList(),
      List(Left(ObsError.Overflow(resource = "diagnostics"))),
    )
