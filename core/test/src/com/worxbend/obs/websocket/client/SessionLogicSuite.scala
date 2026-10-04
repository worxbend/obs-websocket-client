package com.worxbend.obs.websocket.client

import com.worxbend.obs.websocket.client.protocol.*
import munit.FunSuite
import ox.channels.{Channel, ChannelClosed}
import ox.discard

/** Pure scripted actor invocations isolate state transitions and bounded queue decisions. */
class SessionLogicSuite extends FunSuite:
  private val empty = JsonObject.empty
  private def number(value: Int): JsonValue = JsonValue.Num(BigDecimal(value))
  private def obj(fields: (String, JsonValue)*): JsonObject = JsonObject(fields.toMap)
  private def okResponse: JsonObject = obj(
    "requestType" -> JsonValue.Str("Echo"),
    "requestStatus" -> obj("result" -> JsonValue.Bool(true), "code" -> number(100))
  )

  private class Harness(capacity: Int = 1, config: ObsConfig = ObsConfig()):
    val outgoing = Channel.buffered[WireMessage](capacity)
    val identified = Channel.buffered[Either[ObsError, ConnectionMetadata]](1)
    val logic = new SessionLogic(config, None, outgoing, identified)
    def ready(): Unit =
      logic
        .incoming(WireMessage(0, obj("rpcVersion" -> number(1), "obsWebSocketVersion" -> JsonValue.Str("5.6"))))
        .discard
      outgoing.receive().discard
      logic.incoming(WireMessage(2, obj("negotiatedRpcVersion" -> number(1)))).discard
      identified.receive().discard
    def request(id: String): WireMessage = WireMessage(6, obj("requestId" -> JsonValue.Str(id)))
    def reply(): Channel[Either[ObsError, JsonObject]] = Channel.buffered(1)
    def register(id: String, channel: Channel[Either[ObsError, JsonObject]]): Either[ObsError, Unit] =
      logic.register(id, "Echo", 7, request(id), channel)

  test("duplicate request cleanup preserves original owner; bounded writer rejects extra requests"):
    val h = new Harness
    h.ready()
    val original = h.reply()
    val duplicate = h.reply()
    assertEquals(h.register("same", original), Right(()))
    assertEquals(
      h.register("same", duplicate),
      Left(ObsError.InternalError("Request ID generator produced a duplicate ID"))
    )
    h.logic.cancel("same", duplicate)
    assert(h.logic.canSend(h.request("same")))
    assertEquals(h.register("overflow", duplicate), Left(ObsError.Overflow("outgoing queue")))
    assert(!h.logic.canSend(h.request("overflow")))
    h.logic.cancel("same", original)
    assert(!h.logic.canSend(h.request("same")))
    h.logic.close()
    assert(!h.logic.canSend(WireMessage(3, empty)))

  test("duplicate subscription cleanup preserves original; removed subscriptions are completed"):
    val h = new Harness
    h.ready()
    val original = Channel.buffered[Event](1)
    val duplicate = Channel.buffered[Event](1)
    assertEquals(h.logic.subscribe("same", original, Set.empty, OverflowPolicy.Fail), Right(()))
    assertEquals(
      h.logic.subscribe("same", duplicate, Set.empty, OverflowPolicy.Fail),
      Left(ObsError.InternalError("Duplicate subscription ID"))
    )
    h.logic.unsubscribe("same", duplicate)
    h.logic
      .incoming(
        WireMessage(5, obj("eventIntent" -> number(1), "eventType" -> JsonValue.Str("Future"), "eventData" -> empty))
      )
      .discard
    assertEquals(original.receive().eventType, "Future")
    h.logic.unsubscribe("same", original)
    assertEquals(new ObsSubscription(original, () => 0L).next(), Left(ObsError.Closed))
    assertEquals(h.logic.losses("missing"), 0L)

  test("closed subscriber is removed without poisoning response handling"):
    val h = new Harness
    h.ready()
    val events = Channel.buffered[Event](1)
    assertEquals(h.logic.subscribe("s", events, Set.empty, OverflowPolicy.Fail), Right(()))
    events.done()
    h.logic
      .incoming(
        WireMessage(5, obj("eventIntent" -> number(1), "eventType" -> JsonValue.Str("Future"), "eventData" -> empty))
      )
      .discard
    assertEquals(h.logic.phase, ConnectionState.Ready)

  test("session failure preserves per-subscription drop counts for diagnostics"):
    val h = new Harness
    h.ready()
    val events = Channel.buffered[Event](1)
    val dropped = new java.util.concurrent.atomic.AtomicLong(0L)
    assertEquals(h.logic.subscribe("s", events, Set.empty, OverflowPolicy.DropNewest, dropped = dropped), Right(()))
    val frame = WireMessage(
      5,
      obj("eventIntent" -> number(1), "eventType" -> JsonValue.Str("Future"), "eventData" -> empty)
    )
    assertEquals(h.logic.incoming(frame), true)
    assertEquals(h.logic.incoming(frame), true)
    assertEquals(h.logic.losses("s"), 1L)
    h.logic.fail(ObsError.Transport("lost"))
    assertEquals(dropped.get(), 1L)
    assertEquals(h.logic.losses("s"), 0L)
    assertEquals(h.logic.losses("missing"), 0L)

  test("a hello missing every required field fails the handshake"):
    val h = new Harness
    // A failing frame tells the reader to stop consuming.
    assertEquals(h.logic.incoming(WireMessage(0, empty)), false)
    assertEquals(h.logic.phase, ConnectionState.Failed)
    assert(h.identified.receive().isLeft)

  test("a hello without the server version fails the handshake"):
    val h = new Harness
    assertEquals(h.logic.incoming(WireMessage(0, obj("rpcVersion" -> number(1)))), false)
    assertEquals(h.logic.phase, ConnectionState.Failed)
    assert(h.identified.receive().isLeft)

  test("a hello with a non-object authentication block fails the handshake"):
    val h = new Harness
    val data = obj(
      "rpcVersion" -> number(1),
      "obsWebSocketVersion" -> JsonValue.Str("5"),
      "authentication" -> JsonValue.Str("invalid")
    )
    assertEquals(h.logic.incoming(WireMessage(0, data)), false)
    assertEquals(h.logic.phase, ConnectionState.Failed)
    assert(h.identified.receive().isLeft)

  test("a hello with an empty authentication block fails the handshake"):
    val h = new Harness
    val data = obj("rpcVersion" -> number(1), "obsWebSocketVersion" -> JsonValue.Str("5"), "authentication" -> empty)
    assertEquals(h.logic.incoming(WireMessage(0, data)), false)
    assertEquals(h.logic.phase, ConnectionState.Failed)
    assert(h.identified.receive().isLeft)

  test("a hello with authentication missing the challenge fails the handshake"):
    val h = new Harness
    val data = obj(
      "rpcVersion" -> number(1),
      "obsWebSocketVersion" -> JsonValue.Str("5"),
      "authentication" -> obj("salt" -> JsonValue.Str("s"))
    )
    assertEquals(h.logic.incoming(WireMessage(0, data)), false)
    assertEquals(h.logic.phase, ConnectionState.Failed)
    assert(h.identified.receive().isLeft)

  test("messages after handshake failure and close are rejected"):
    val h = new Harness
    assertEquals(h.logic.incoming(WireMessage(0, empty)), false)
    assertEquals(h.logic.incoming(WireMessage(0, empty)), false)
    h.logic.fail(ObsError.Transport("second failure"))
    h.logic.close()
    assertEquals(h.logic.incoming(WireMessage(0, empty)), false)
    assertEquals(h.logic.phase, ConnectionState.Closed)

  test("incompatible negotiated RPC and malformed events fail session"):
    val h = new Harness
    h.logic
      .incoming(WireMessage(0, obj("rpcVersion" -> number(1), "obsWebSocketVersion" -> JsonValue.Str("5"))))
      .discard
    h.logic.incoming(WireMessage(2, obj("negotiatedRpcVersion" -> number(2)))).discard
    assertEquals(h.identified.receive(), Left(ObsError.IncompatibleProtocol(2)))
    for data <- Vector(
        empty,
        obj("eventIntent" -> number(1), "eventType" -> JsonValue.Str("Future"), "eventData" -> JsonValue.Null),
        obj(
          "eventIntent" -> number(1),
          "eventType" -> JsonValue.Str("CurrentProgramSceneChanged"),
          "eventData" -> empty
        )
      )
    do
      val e = new Harness
      e.ready()
      e.logic.incoming(WireMessage(5, data)).discard
      assertEquals(e.logic.phase, ConnectionState.Failed)

  test("mismatched response opcode fails pending request"):
    val h = new Harness
    h.ready()
    val reply = h.reply()
    assertEquals(h.register("a", reply), Right(()))
    h.logic.incoming(WireMessage(9, obj("requestId" -> JsonValue.Str("a")))).discard
    assert(reply.receive().left.exists(_.isInstanceOf[ObsError.MalformedPayload]))
    assertEquals(h.logic.phase, ConnectionState.Failed)

  test("a valid response envelope returns its omitted data as empty"):
    assertEquals(SessionWire.responseData(okResponse, "Echo", "a"), Right(empty))

  test("a response with a mismatched request type is rejected"):
    assert(SessionWire.responseData(okResponse, "Other", "a").isLeft)

  test("a response envelope without a request type is malformed"):
    assert(SessionWire.responseData(empty, "Echo", "a").isLeft)

  test("a response without requestStatus is malformed"):
    assert(SessionWire.responseData(obj("requestType" -> JsonValue.Str("Echo")), "Echo", "a").isLeft)

  test("a response with an empty requestStatus is malformed"):
    val invalid = obj("requestType" -> JsonValue.Str("Echo"), "requestStatus" -> empty)
    assert(SessionWire.responseData(invalid, "Echo", "a").isLeft)

  test("an explicit null responseData is malformed"):
    val invalid = okResponse.copy(fields = okResponse.fields.updated("responseData", JsonValue.Null))
    assert(SessionWire.responseData(invalid, "Echo", "a").isLeft)

  test("a requestStatus without a status code is malformed"):
    val invalid =
      okResponse.copy(fields = okResponse.fields.updated("requestStatus", obj("result" -> JsonValue.Bool(true))))
    assert(SessionWire.responseData(invalid, "Echo", "a").isLeft)

  test("a requestStatus with a non-string comment is malformed"):
    val invalid = okResponse.copy(fields =
      okResponse.fields.updated(
        "requestStatus",
        obj("result" -> JsonValue.Bool(true), "code" -> number(100), "comment" -> number(1))
      )
    )
    assert(SessionWire.responseData(invalid, "Echo", "a").isLeft)

  test("a rejected response preserves its status code and comment"):
    val rejected = okResponse.copy(fields =
      okResponse.fields.updated(
        "requestStatus",
        obj("result" -> JsonValue.Bool(false), "code" -> number(500), "comment" -> JsonValue.Str("not found"))
      )
    )
    assertEquals(
      SessionWire.responseData(rejected, "Echo", "id"),
      Left(ObsError.RequestRejected("Echo", "id", 500, Some("not found")))
    )

  test("invalid lifecycle transitions and closed writer are explicit failures"):
    val h = new Harness
    h.logic.transition(ConnectionState.Ready)
    assertEquals(h.logic.phase, ConnectionState.Failed)
    assert(h.identified.receive().left.exists(_.isInstanceOf[ObsError.InternalError]))
    val closed = new Harness
    closed.ready()
    closed.outgoing.done()
    assertEquals(closed.register("a", closed.reply()), Left(ObsError.Closed))
    assert(!closed.logic.canSend(closed.request("a")))

  test("deterministic send rejection completes only the affected request or batch"):
    val error = ObsError.MessageTooLarge("Outgoing message exceeds configured byte limit")
    val h = new Harness(capacity = 4)
    h.ready()
    val requestReply = h.reply()
    val batchReply = h.reply()
    assertEquals(h.register("request", requestReply), Right(()))
    assertEquals(h.register("batch", batchReply), Right(()))
    h.logic.sendRejected(WireMessage(6, obj("requestId" -> JsonValue.Str("request"))), error)
    assertEquals(requestReply.receive(), Left(error))
    h.logic.sendRejected(WireMessage(8, obj("requestId" -> JsonValue.Str("batch"))), error)
    assertEquals(batchReply.receive(), Left(error))
    assertEquals(h.logic.phase, ConnectionState.Ready)
    h.logic.sendRejected(WireMessage(6, obj("requestId" -> JsonValue.Str("request"))), error)
    h.logic.sendRejected(WireMessage(6, empty), error)
    h.logic.sendRejected(WireMessage(8, empty), error)
    assertEquals(h.logic.phase, ConnectionState.Ready)
    h.logic.sendRejected(WireMessage(3, empty), error)
    assertEquals(h.logic.phase, ConnectionState.Failed)

  test("missing negotiated RPC and response ID are malformed protocol messages"):
    val h = new Harness
    h.logic
      .incoming(WireMessage(0, obj("rpcVersion" -> number(1), "obsWebSocketVersion" -> JsonValue.Str("5"))))
      .discard
    h.logic.incoming(WireMessage(2, empty)).discard
    assert(h.identified.receive().left.exists(_.isInstanceOf[ObsError.MalformedPayload]))
    val ready = new Harness
    ready.ready()
    ready.logic.incoming(WireMessage(7, empty)).discard
    assertEquals(ready.logic.phase, ConnectionState.Failed)

  test("drop-oldest counts actual loss when consumer or shutdown wins the replacement race"):
    val event = Event.decode("Future", empty).toOption.get
    assertEquals(SubscriptionDelivery.replaceOldest(() => Some(event), () => true), 1L)
    assertEquals(SubscriptionDelivery.replaceOldest(() => None, () => true), 0L)
    assertEquals(SubscriptionDelivery.replaceOldest(() => Some(event), () => false), 2L)
    assertEquals(SubscriptionDelivery.replaceOldest(() => ChannelClosed.Done, () => ChannelClosed.Done), 1L)

  test("events with no payload accept omitted eventData"):
    val h = new Harness
    h.ready()
    val events = Channel.buffered[Event](1)
    assertEquals(h.logic.subscribe("s", events, Set.empty, OverflowPolicy.Fail), Right(()))
    h.logic
      .incoming(WireMessage(5, obj("eventIntent" -> number(1), "eventType" -> JsonValue.Str("ExitStarted"))))
      .discard
    val event = events.receive()
    assertEquals(event.eventType, "ExitStarted")
    assertEquals(event.eventData, empty)
    assertEquals(h.logic.phase, ConnectionState.Ready)

  test("event intent is required, numeric, integral and nonnegative"):
    val invalid = Vector(
      None,
      Some(JsonValue.Str("4")),
      Some(JsonValue.Num(BigDecimal(-1))),
      Some(JsonValue.Num(BigDecimal("0.5")))
    )
    for intent <- invalid do
      val h = new Harness
      h.ready()
      val data = obj("eventType" -> JsonValue.Str("ExitStarted"))
      h.logic.incoming(WireMessage(5, data.copy(fields = data.fields ++ intent.map("eventIntent" -> _)))).discard
      assertEquals(h.logic.phase, ConnectionState.Failed)
      assert(
        h.identified
          .receive()
          .left
          .exists:
            case ObsError.MalformedPayload("eventIntent", _) => true
            case _                                           => false
      )

  test("multiple reidentify acknowledgements preserve readiness and reject unsolicited duplicates"):
    val h = new Harness(capacity = 2)
    h.ready()
    assertEquals(h.logic.reidentify(EventSubscriptions.none), Right(()))
    assertEquals(h.logic.reidentify(EventSubscriptions.normal), Right(()))
    assert(h.logic.reidentify(EventSubscriptions.none).isLeft)
    val ack = WireMessage(2, obj("negotiatedRpcVersion" -> number(1)))
    assert(h.logic.incoming(ack))
    assert(h.logic.incoming(ack))
    assertEquals(h.logic.phase, ConnectionState.Ready)
    assert(!h.logic.incoming(ack))

  test("reidentify acknowledgement backlog is bounded independently of the writer"):
    val h = new Harness(config = ObsConfig(maxInFlight = 1))
    h.ready()
    for _ <- 1 to SessionLogic.maxReidentifyAcks do
      assertEquals(h.logic.reidentify(EventSubscriptions.none), Right(()))
      h.outgoing.receive().discard
    assertEquals(h.logic.reidentify(EventSubscriptions.normal), Left(ObsError.Overflow("reidentify acknowledgements")))

  test("malformed and incompatible reidentify acknowledgements fail the session"):
    for data <- Vector(empty, obj("negotiatedRpcVersion" -> number(2))) do
      val h = new Harness
      h.ready()
      assertEquals(h.logic.reidentify(EventSubscriptions.none), Right(()))
      assert(!h.logic.incoming(WireMessage(2, data)))

  test("diagnostic registration preserves ownership and closed sessions reject observers"):
    val h = new Harness
    val events = Channel.buffered[SessionDiagnostic](1)
    val other = Channel.buffered[SessionDiagnostic](1)
    assertEquals(h.logic.subscribeDiagnostics("observer", events), Left(ObsError.Closed))
    h.ready()
    assertEquals(h.logic.subscribeDiagnostics("observer", events), Right(()))
    assert(h.logic.subscribeDiagnostics("observer", other).isLeft)
    h.logic.unsubscribeDiagnostics("observer", other)
    h.logic.traffic(TrafficDirection.Sent, 7)
    h.logic.traffic(TrafficDirection.Received, 9)
    assertEquals(h.logic.diagnosticLosses("observer"), 1L)
    assertEquals(h.logic.diagnosticLosses("absent"), 0L)
    assertEquals(h.logic.statistics.sentBytes, 7L)
    assertEquals(h.logic.statistics.receivedBytes, 9L)
    h.logic.requestFinished("Echo", "id", 12L, DiagnosticOutcome.Failed)
    assertEquals(h.logic.statistics.failedRequests, 1L)
    assertEquals(h.logic.statistics.requestElapsedNanos, 12L)
    h.logic.unsubscribeDiagnostics("observer", events)
    assertEquals(h.logic.diagnosticLosses("observer"), 0L)
    h.logic.close()

  test("normal close reports Closed and keeps terminal diagnostic loss counts"):
    val h = new Harness
    h.ready()
    val events = Channel.buffered[SessionDiagnostic](1)
    h.logic.subscribeDiagnostics("observer", events).toOption.get
    h.logic.traffic(TrafficDirection.Sent, 1)
    h.logic.close()
    assertEquals(h.logic.diagnosticLosses("observer"), 1L)
    h.logic.requestFinished("Echo", "id", 1L, DiagnosticOutcome.Cancelled)
    assertEquals(h.logic.diagnosticLosses("observer"), 1L)
    assertEquals(events.receive(), SessionDiagnostic.Traffic(TrafficDirection.Sent, 1))
    val closed = new Harness
    closed.ready()
    val channel = Channel.buffered[SessionDiagnostic](1)
    closed.logic.subscribeDiagnostics("observer", channel).toOption.get
    closed.logic.close()
    assertEquals(channel.receive(), SessionDiagnostic.StateChanged(ConnectionState.Closed))

  test("repeated unsubscribe preserves owned counters without retaining registry history"):
    val h = new Harness
    h.ready()
    val frame =
      WireMessage(5, obj("eventIntent" -> number(1), "eventType" -> JsonValue.Str("Future"), "eventData" -> empty))
    for index <- 1 to 100 do
      val id = s"losses-$index"
      val events = Channel.buffered[Event](1)
      val dropped = new java.util.concurrent.atomic.AtomicLong(0L)
      h.logic.subscribe(id, events, Set.empty, OverflowPolicy.DropNewest, dropped = dropped).toOption.get
      assert(h.logic.incoming(frame))
      assert(h.logic.incoming(frame))
      h.logic.unsubscribe(id, events)
      assertEquals(dropped.get(), 1L)
      assertEquals(h.logic.losses(id), 0L)
