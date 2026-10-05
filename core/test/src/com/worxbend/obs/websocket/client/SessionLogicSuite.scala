package com.worxbend.obs.websocket.client

import com.worxbend.obs.websocket.client.protocol.*
import munit.FunSuite
import ox.channels.{Channel, ChannelClosed}
import ox.discard

/** Pure scripted actor invocations isolate state transitions and bounded queue decisions. */
class SessionLogicSuite extends FunSuite:
  private val empty                                           = JsonObject.empty
  private val syntheticPassword: String                       = "synthetic-test-password"
  private def number(value: Int): JsonValue                   = JsonValue.Num(value = BigDecimal(value))
  private def obj(fields:   (String, JsonValue)*): JsonObject = JsonObject(fields = fields.toMap)
  private def okResponse: JsonObject                          = obj(
    "requestType"   -> JsonValue.Str(value = "Echo"),
    "requestStatus" -> obj("result" -> JsonValue.Bool(value = true), "code" -> number(value = 100)),
  )

  private class Harness(capacity: Int = 1, config: ObsConfig = ObsConfig(), password: Option[String] = None):
    val outgoing   = Channel.buffered[WireMessage](capacity)
    val identified = Channel.buffered[Either[ObsError, ConnectionMetadata]](1)
    val logic      =
      new SessionLogic(
        config                 = config,
        authenticationPassword = password,
        outgoing               = outgoing,
        identified             = identified,
      )
    def ready(): Unit =
      logic
        .incoming(message =
          WireMessage(
            op   = 0,
            data = obj("rpcVersion" -> number(value = 1), "obsWebSocketVersion" -> JsonValue.Str(value = "5.6")),
          )
        )
        .discard
      outgoing.receive().discard
      logic.incoming(message = WireMessage(op = 2, data = obj("negotiatedRpcVersion" -> number(value = 1)))).discard
      identified.receive().discard
    def request(id: String): WireMessage = WireMessage(op = 6, data = obj("requestId" -> JsonValue.Str(value = id)))
    def reply(): Channel[Either[ObsError, JsonObject]] = Channel.buffered(1)
    def register(id: String, channel: Channel[Either[ObsError, JsonObject]]): Either[ObsError, Unit] =
      logic.register(id = id, requestType = "Echo", opcode = 7, message = request(id = id), reply = channel)

  test("duplicate request cleanup preserves original owner; bounded writer rejects extra requests"):
    val h = new Harness
    h.ready()
    val original  = h.reply()
    val duplicate = h.reply()
    assertEquals(h.register(id = "same", channel = original), Right(()))
    assertEquals(
      h.register(id = "same", channel = duplicate),
      Left(ObsError.InternalError(message = "Request ID generator produced a duplicate ID")),
    )
    h.logic.cancel(id = "same", owner = duplicate)
    assert(h.logic.canSend(message = h.request(id = "same")))
    assertEquals(h.register(id = "overflow", channel = duplicate), Left(ObsError.Overflow(resource = "outgoing queue")))
    assert(!h.logic.canSend(message = h.request(id = "overflow")))
    h.logic.cancel(id = "same", owner = original)
    assert(!h.logic.canSend(message = h.request(id = "same")))
    h.logic.close()
    assert(!h.logic.canSend(message = WireMessage(op = 3, data = empty)))

  test("duplicate subscription cleanup preserves original; removed subscriptions are completed"):
    val h = new Harness
    h.ready()
    val original  = Channel.buffered[Event](1)
    val duplicate = Channel.buffered[Event](1)
    assertEquals(
      h.logic.subscribe(id = "same", channel = original, types = Set.empty, policy = OverflowPolicy.Fail),
      Right(()),
    )
    assertEquals(
      h.logic.subscribe(id = "same", channel = duplicate, types = Set.empty, policy = OverflowPolicy.Fail),
      Left(ObsError.InternalError(message = "Duplicate subscription ID")),
    )
    h.logic.unsubscribe(id = "same", owner = duplicate)
    h.logic
      .incoming(
        message = WireMessage(
          op   = 5,
          data = obj(
            "eventIntent" -> number(value = 1),
            "eventType"   -> JsonValue.Str(value = "Future"),
            "eventData"   -> empty,
          ),
        )
      )
      .discard
    assertEquals(original.receive().eventType, "Future")
    h.logic.unsubscribe(id = "same", owner = original)
    assertEquals(new ObsSubscription(channel = original, () => 0L).next(), Next.Ended)
    assertEquals(h.logic.losses(id = "missing"), 0L)

  test("closed subscriber is removed without poisoning response handling"):
    val h = new Harness
    h.ready()
    val events = Channel.buffered[Event](1)
    assertEquals(
      h.logic.subscribe(id = "s", channel = events, types = Set.empty, policy = OverflowPolicy.Fail),
      Right(()),
    )
    events.done()
    h.logic
      .incoming(
        message = WireMessage(
          op   = 5,
          data = obj(
            "eventIntent" -> number(value = 1),
            "eventType"   -> JsonValue.Str(value = "Future"),
            "eventData"   -> empty,
          ),
        )
      )
      .discard
    assertEquals(h.logic.phase, ConnectionState.Ready)

  test("session failure preserves per-subscription drop counts for diagnostics"):
    val h = new Harness
    h.ready()
    val events  = Channel.buffered[Event](1)
    val dropped = new java.util.concurrent.atomic.AtomicLong(0L)
    assertEquals(
      h.logic.subscribe(
        id      = "s",
        channel = events,
        types   = Set.empty,
        policy  = OverflowPolicy.DropNewest,
        dropped = dropped,
      ),
      Right(()),
    )
    val frame = WireMessage(
      op   = 5,
      data =
        obj("eventIntent" -> number(value = 1), "eventType" -> JsonValue.Str(value = "Future"), "eventData" -> empty),
    )
    assertEquals(h.logic.incoming(message = frame), true)
    assertEquals(h.logic.incoming(message = frame), true)
    assertEquals(h.logic.losses(id = "s"), 1L)
    h.logic.fail(error = ObsError.Transport(message = "lost"))
    assertEquals(dropped.get(), 1L)
    assertEquals(h.logic.losses(id = "s"), 0L)
    assertEquals(h.logic.losses(id = "missing"), 0L)

  test("a hello missing every required field fails the handshake"):
    val h = new Harness
    // A failing frame tells the reader to stop consuming.
    assertEquals(h.logic.incoming(message = WireMessage(op = 0, data = empty)), false)
    assertEquals(h.logic.phase, ConnectionState.Failed)
    assert(h.identified.receive().isLeft)

  test("a hello without the server version fails the handshake"):
    val h = new Harness
    assertEquals(h.logic.incoming(message = WireMessage(op = 0, data = obj("rpcVersion" -> number(value = 1)))), false)
    assertEquals(h.logic.phase, ConnectionState.Failed)
    assert(h.identified.receive().isLeft)

  test("a hello with a non-object authentication block fails the handshake"):
    val h    = new Harness
    val data = obj(
      "rpcVersion"          -> number(value = 1),
      "obsWebSocketVersion" -> JsonValue.Str(value = "5"),
      "authentication"      -> JsonValue.Str(value = "invalid"),
    )
    assertEquals(h.logic.incoming(message = WireMessage(op = 0, data = data)), false)
    assertEquals(h.logic.phase, ConnectionState.Failed)
    assert(h.identified.receive().isLeft)

  test("a hello with an empty authentication block fails the handshake"):
    val h    = new Harness
    val data = obj(
      "rpcVersion"          -> number(value = 1),
      "obsWebSocketVersion" -> JsonValue.Str(value = "5"),
      "authentication"      -> empty,
    )
    assertEquals(h.logic.incoming(message = WireMessage(op = 0, data = data)), false)
    assertEquals(h.logic.phase, ConnectionState.Failed)
    assert(h.identified.receive().isLeft)

  test("a hello with authentication missing the challenge fails the handshake"):
    val h    = new Harness
    val data = obj(
      "rpcVersion"          -> number(value = 1),
      "obsWebSocketVersion" -> JsonValue.Str(value = "5"),
      "authentication"      -> obj("salt" -> JsonValue.Str(value = "s")),
    )
    assertEquals(h.logic.incoming(message = WireMessage(op = 0, data = data)), false)
    assertEquals(h.logic.phase, ConnectionState.Failed)
    assert(h.identified.receive().isLeft)

  test("messages after handshake failure and close are rejected"):
    val h = new Harness
    assertEquals(h.logic.incoming(message = WireMessage(op = 0, data = empty)), false)
    assertEquals(h.logic.incoming(message = WireMessage(op = 0, data = empty)), false)
    h.logic.fail(error = ObsError.Transport(message = "second failure"))
    h.logic.close()
    assertEquals(h.logic.incoming(message = WireMessage(op = 0, data = empty)), false)
    assertEquals(h.logic.phase, ConnectionState.Closed)

  test("incompatible negotiated RPC and malformed events fail session"):
    val h = new Harness
    h.logic
      .incoming(message =
        WireMessage(
          op   = 0,
          data = obj("rpcVersion" -> number(value = 1), "obsWebSocketVersion" -> JsonValue.Str(value = "5")),
        )
      )
      .discard
    h.logic.incoming(message = WireMessage(op = 2, data = obj("negotiatedRpcVersion" -> number(value = 2)))).discard
    assertEquals(h.identified.receive(), Left(ObsError.IncompatibleProtocol(version = 2)))
    for data <- Vector(
                  empty,
                  obj(
                    "eventIntent" -> number(value = 1),
                    "eventType"   -> JsonValue.Str(value = "Future"),
                    "eventData"   -> JsonValue.Null,
                  ),
                  obj(
                    "eventIntent" -> number(value = 1),
                    "eventType"   -> JsonValue.Str(value = "CurrentProgramSceneChanged"),
                    "eventData"   -> empty,
                  ),
                )
    do
      val e = new Harness
      e.ready()
      e.logic.incoming(message = WireMessage(op = 5, data = data)).discard
      assertEquals(e.logic.phase, ConnectionState.Failed)

  test("mismatched response opcode fails pending request"):
    val h = new Harness
    h.ready()
    val reply = h.reply()
    assertEquals(h.register(id = "a", channel = reply), Right(()))
    h.logic.incoming(message = WireMessage(op = 9, data = obj("requestId" -> JsonValue.Str(value = "a")))).discard
    assert(reply.receive().left.exists(_.isInstanceOf[ObsError.MalformedPayload]))
    assertEquals(h.logic.phase, ConnectionState.Failed)

  test("a valid response envelope returns its omitted data as empty"):
    assertEquals(SessionWire.responseData(data = okResponse, expectedType = "Echo", id = "a"), Right(empty))

  test("a response with a mismatched request type is rejected"):
    assert(SessionWire.responseData(data = okResponse, expectedType = "Other", id = "a").isLeft)

  test("a response envelope without a request type is malformed"):
    assert(SessionWire.responseData(data = empty, expectedType = "Echo", id = "a").isLeft)

  test("a response without requestStatus is malformed"):
    assert(
      SessionWire
        .responseData(data = obj("requestType" -> JsonValue.Str(value = "Echo")), expectedType = "Echo", id = "a")
        .isLeft
    )

  test("a response with an empty requestStatus is malformed"):
    val invalid = obj("requestType" -> JsonValue.Str(value = "Echo"), "requestStatus" -> empty)
    assert(SessionWire.responseData(data = invalid, expectedType = "Echo", id = "a").isLeft)

  test("an explicit null responseData is malformed"):
    val invalid = okResponse.copy(fields = okResponse.fields.updated("responseData", JsonValue.Null))
    assert(SessionWire.responseData(data = invalid, expectedType = "Echo", id = "a").isLeft)

  test("a requestStatus without a status code is malformed"):
    val invalid =
      okResponse.copy(fields =
        okResponse.fields.updated("requestStatus", obj("result" -> JsonValue.Bool(value = true)))
      )
    assert(SessionWire.responseData(data = invalid, expectedType = "Echo", id = "a").isLeft)

  test("a requestStatus with a non-string comment is malformed"):
    val invalid = okResponse.copy(fields =
      okResponse.fields.updated(
        "requestStatus",
        obj("result" -> JsonValue.Bool(value = true), "code" -> number(value = 100), "comment" -> number(value = 1)),
      )
    )
    assert(SessionWire.responseData(data = invalid, expectedType = "Echo", id = "a").isLeft)

  test("a rejected response preserves its status code and comment"):
    val rejected = okResponse.copy(fields =
      okResponse.fields.updated(
        "requestStatus",
        obj(
          "result"  -> JsonValue.Bool(value = false),
          "code"    -> number(value = 500),
          "comment" -> JsonValue.Str(value = "not found"),
        ),
      )
    )
    assertEquals(
      SessionWire.responseData(data = rejected, expectedType = "Echo", id = "id"),
      Left(ObsError.RequestRejected(requestType = "Echo", requestId = "id", code = 500, comment = Some("not found"))),
    )

  test("invalid lifecycle transitions and closed writer are explicit failures"):
    val h = new Harness
    h.logic.transition(to = ConnectionState.Ready)
    assertEquals(h.logic.phase, ConnectionState.Failed)
    assert(h.identified.receive().left.exists(_.isInstanceOf[ObsError.InternalError]))
    val closed = new Harness
    closed.ready()
    closed.outgoing.done()
    assertEquals(closed.register(id = "a", channel = closed.reply()), Left(ObsError.Closed))
    assert(!closed.logic.canSend(message = closed.request(id = "a")))

  test("deterministic send rejection completes only the affected request or batch"):
    val error = ObsError.MessageTooLarge(message = "Outgoing message exceeds configured byte limit")
    val h     = new Harness(capacity = 4)
    h.ready()
    val requestReply = h.reply()
    val batchReply   = h.reply()
    assertEquals(h.register(id = "request", channel = requestReply), Right(()))
    assertEquals(h.register(id = "batch", channel = batchReply), Right(()))
    h.logic.sendRejected(
      message = WireMessage(op = 6, data = obj("requestId" -> JsonValue.Str(value = "request"))),
      error   = error,
    )
    assertEquals(requestReply.receive(), Left(error))
    h.logic.sendRejected(
      message = WireMessage(op = 8, data = obj("requestId" -> JsonValue.Str(value = "batch"))),
      error   = error,
    )
    assertEquals(batchReply.receive(), Left(error))
    assertEquals(h.logic.phase, ConnectionState.Ready)
    h.logic.sendRejected(
      message = WireMessage(op = 6, data = obj("requestId" -> JsonValue.Str(value = "request"))),
      error   = error,
    )
    h.logic.sendRejected(message = WireMessage(op = 6, data = empty), error = error)
    h.logic.sendRejected(message = WireMessage(op = 8, data = empty), error = error)
    assertEquals(h.logic.phase, ConnectionState.Ready)
    h.logic.sendRejected(message = WireMessage(op = 3, data = empty), error = error)
    assertEquals(h.logic.phase, ConnectionState.Failed)

  test("missing negotiated RPC and response ID are malformed protocol messages"):
    val h = new Harness
    h.logic
      .incoming(message =
        WireMessage(
          op   = 0,
          data = obj("rpcVersion" -> number(value = 1), "obsWebSocketVersion" -> JsonValue.Str(value = "5")),
        )
      )
      .discard
    h.logic.incoming(message = WireMessage(op = 2, data = empty)).discard
    assert(h.identified.receive().left.exists(_.isInstanceOf[ObsError.MalformedPayload]))
    val ready = new Harness
    ready.ready()
    ready.logic.incoming(message = WireMessage(op = 7, data = empty)).discard
    assertEquals(ready.logic.phase, ConnectionState.Failed)

  test("drop-oldest counts actual loss when consumer or shutdown wins the replacement race"):
    val event = Event.decode(eventType = "Future", data = empty).toOption.get
    assertEquals(SubscriptionDelivery.replaceOldest(() => Some(event), () => true), 1L)
    assertEquals(SubscriptionDelivery.replaceOldest(() => None, () => true), 0L)
    assertEquals(SubscriptionDelivery.replaceOldest(() => Some(event), () => false), 2L)
    assertEquals(SubscriptionDelivery.replaceOldest(() => ChannelClosed.Done, () => ChannelClosed.Done), 1L)

  test("events with no payload accept omitted eventData"):
    val h = new Harness
    h.ready()
    val events = Channel.buffered[Event](1)
    assertEquals(
      h.logic.subscribe(id = "s", channel = events, types = Set.empty, policy = OverflowPolicy.Fail),
      Right(()),
    )
    h.logic
      .incoming(message =
        WireMessage(
          op   = 5,
          data = obj("eventIntent" -> number(value = 1), "eventType" -> JsonValue.Str(value = "ExitStarted")),
        )
      )
      .discard
    val event = events.receive()
    assertEquals(event.eventType, "ExitStarted")
    assertEquals(event.eventData, empty)
    assertEquals(h.logic.phase, ConnectionState.Ready)

  test("event intent is required, numeric, integral and nonnegative"):
    val invalid = Vector(
      None,
      Some(JsonValue.Str(value = "4")),
      Some(JsonValue.Num(value = BigDecimal(-1))),
      Some(JsonValue.Num(value = BigDecimal("0.5"))),
    )
    for intent <- invalid do
      val h = new Harness
      h.ready()
      val data = obj("eventType" -> JsonValue.Str(value = "ExitStarted"))
      h.logic
        .incoming(message =
          WireMessage(op = 5, data = data.copy(fields = data.fields ++ intent.map("eventIntent" -> _)))
        )
        .discard
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
    assertEquals(h.logic.reidentify(mask = EventSubscriptions.none), Right(()))
    assertEquals(h.logic.reidentify(mask = EventSubscriptions.normal), Right(()))
    assert(h.logic.reidentify(mask = EventSubscriptions.none).isLeft)
    val ack = WireMessage(op = 2, data = obj("negotiatedRpcVersion" -> number(value = 1)))
    assert(h.logic.incoming(message = ack))
    assert(h.logic.incoming(message = ack))
    assertEquals(h.logic.phase, ConnectionState.Ready)
    assert(!h.logic.incoming(message = ack))

  test("reidentify acknowledgement backlog is bounded independently of the writer"):
    val h = new Harness(config = ObsConfig(maxInFlight = 1))
    h.ready()
    for _ <- 1 to SessionLogic.maxReidentifyAcks do
      assertEquals(h.logic.reidentify(mask = EventSubscriptions.none), Right(()))
      h.outgoing.receive().discard
    assertEquals(
      h.logic.reidentify(mask = EventSubscriptions.normal),
      Left(ObsError.Overflow(resource = "reidentify acknowledgements")),
    )

  test("malformed and incompatible reidentify acknowledgements fail the session"):
    for data <- Vector(empty, obj("negotiatedRpcVersion" -> number(value = 2))) do
      val h = new Harness
      h.ready()
      assertEquals(h.logic.reidentify(mask = EventSubscriptions.none), Right(()))
      assert(!h.logic.incoming(message = WireMessage(op = 2, data = data)))

  test("diagnostic registration preserves ownership and closed sessions reject observers"):
    val h      = new Harness
    val events = Channel.buffered[SessionDiagnostic](1)
    val other  = Channel.buffered[SessionDiagnostic](1)
    assertEquals(h.logic.subscribeDiagnostics(id = "observer", channel = events), Left(ObsError.Closed))
    h.ready()
    assertEquals(h.logic.subscribeDiagnostics(id = "observer", channel = events), Right(()))
    assert(h.logic.subscribeDiagnostics(id = "observer", channel = other).isLeft)
    h.logic.unsubscribeDiagnostics(id = "observer", owner                = other)
    h.logic.traffic(direction         = TrafficDirection.Sent, bytes     = 7)
    h.logic.traffic(direction         = TrafficDirection.Received, bytes = 9)
    assertEquals(h.logic.diagnosticLosses(id = "observer"), 1L)
    assertEquals(h.logic.diagnosticLosses(id = "absent"), 0L)
    assertEquals(h.logic.statistics.sentBytes, 7L)
    assertEquals(h.logic.statistics.receivedBytes, 9L)
    h.logic.requestFinished(requestType = "Echo", id = "id", elapsedNanos = 12L, outcome = DiagnosticOutcome.Failed)
    assertEquals(h.logic.statistics.failedRequests, 1L)
    assertEquals(h.logic.statistics.requestElapsedNanos, 12L)
    h.logic.unsubscribeDiagnostics(id = "observer", owner = events)
    assertEquals(h.logic.diagnosticLosses(id = "observer"), 0L)
    h.logic.close()

  test("a password over plaintext ws to a remote host greets every diagnostic subscriber with a warning"):
    val remote = ObsConfig(uri = "ws://obs.internal.example:4455")
    val h      = new Harness(config = remote, password = Some(syntheticPassword))
    h.ready()
    val first  = Channel.buffered[SessionDiagnostic](1)
    val second = Channel.buffered[SessionDiagnostic](1)
    assertEquals(h.logic.subscribeDiagnostics(id = "one", channel = first), Right(()))
    assertEquals(h.logic.subscribeDiagnostics(id = "two", channel = second), Right(()))
    assertEquals(first.tryReceive(), Some(SessionDiagnostic.PlaintextCredentials))
    assertEquals(second.tryReceive(), Some(SessionDiagnostic.PlaintextCredentials))

  test("a password over plaintext ws to a loopback host does not warn"):
    for uri <- Vector("ws://localhost:4455", "ws://127.0.0.1:4455", "ws://[::1]:4455") do
      val h = new Harness(config = ObsConfig(uri = uri), password = Some(syntheticPassword))
      h.ready()
      val channel = Channel.buffered[SessionDiagnostic](1)
      assertEquals(h.logic.subscribeDiagnostics(id = "observer", channel = channel), Right(()))
      assertEquals(channel.tryReceive(), None, s"$uri must not warn")

  test("a password over encrypted wss to a remote host does not warn"):
    val h = new Harness(config = ObsConfig(uri = "wss://obs.internal.example:4455"), password = Some(syntheticPassword))
    h.ready()
    val channel = Channel.buffered[SessionDiagnostic](1)
    assertEquals(h.logic.subscribeDiagnostics(id = "observer", channel = channel), Right(()))
    assertEquals(channel.tryReceive(), None)

  test("plaintext ws to a remote host without a password does not warn"):
    val h = new Harness(config = ObsConfig(uri = "ws://obs.internal.example:4455"))
    h.ready()
    val channel = Channel.buffered[SessionDiagnostic](1)
    assertEquals(h.logic.subscribeDiagnostics(id = "observer", channel = channel), Right(()))
    assertEquals(channel.tryReceive(), None)

  test("normal close reports Closed and keeps terminal diagnostic loss counts"):
    val h = new Harness
    h.ready()
    val events = Channel.buffered[SessionDiagnostic](1)
    h.logic.subscribeDiagnostics(id = "observer", channel = events).toOption.get
    h.logic.traffic(direction = TrafficDirection.Sent, bytes = 1)
    h.logic.close()
    assertEquals(h.logic.diagnosticLosses(id = "observer"), 1L)
    h.logic.requestFinished(requestType = "Echo", id = "id", elapsedNanos = 1L, outcome = DiagnosticOutcome.Cancelled)
    assertEquals(h.logic.diagnosticLosses(id = "observer"), 1L)
    assertEquals(events.receive(), SessionDiagnostic.Traffic(direction = TrafficDirection.Sent, bytes = 1))
    val closed = new Harness
    closed.ready()
    val channel = Channel.buffered[SessionDiagnostic](1)
    closed.logic.subscribeDiagnostics(id = "observer", channel = channel).toOption.get
    closed.logic.close()
    assertEquals(channel.receive(), SessionDiagnostic.StateChanged(state = ConnectionState.Closed))

  test("repeated unsubscribe preserves owned counters without retaining registry history"):
    val h = new Harness
    h.ready()
    val frame =
      WireMessage(
        op   = 5,
        data =
          obj("eventIntent" -> number(value = 1), "eventType" -> JsonValue.Str(value = "Future"), "eventData" -> empty),
      )
    for index <- 1 to 100 do
      val id      = s"losses-$index"
      val events  = Channel.buffered[Event](1)
      val dropped = new java.util.concurrent.atomic.AtomicLong(0L)
      h.logic
        .subscribe(id = id, channel = events, types = Set.empty, policy = OverflowPolicy.DropNewest, dropped = dropped)
        .toOption
        .get
      assert(h.logic.incoming(message = frame))
      assert(h.logic.incoming(message = frame))
      h.logic.unsubscribe(id = id, owner = events)
      assertEquals(dropped.get(), 1L)
      assertEquals(h.logic.losses(id = id), 0L)
