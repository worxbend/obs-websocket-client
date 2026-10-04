package com.worxbend.obs.websocket.client

import com.worxbend.obs.websocket.client.protocol.*
import com.worxbend.obs.websocket.client.protocol.requests.GetSceneList
import munit.FunSuite
import ox.*
import ox.channels.{Channel, ChannelClosed}
import scala.concurrent.duration.*

class SessionSuite extends FunSuite:
  private val empty = JsonObject.empty
  private val config = ObsConfig(handshakeTimeout = 300.millis, requestTimeout = 300.millis)

  private class Peer extends ObsTransport:
    val inbound = Channel.buffered[Either[ObsError, String]](64)
    val sent = Channel.buffered[WireMessage](64)
    val closed = Channel.buffered[Unit](1)
    def send(text: String): Either[ObsError, Unit] =
      Protocol.decode(text).left.map(SessionWire.malformed).map(sent.send)
    def receive(): Either[ObsError, String] = inbound.receiveOrClosed() match
      case value: Either[?, ?] => value.asInstanceOf[Either[ObsError, String]]
      case _: ChannelClosed    => Left(ObsError.Closed)
    def close(): Unit =
      closed.trySendOrClosed(()).discard
      inbound.doneOrClosed().discard
    def emit(op: Int, data: JsonObject): Unit = inbound.send(Right(Protocol.encode(WireMessage(op, data))))
    def hello(auth: Option[JsonObject] = None, rpc: Int = 1): Unit =
      emit(
        0,
        JsonObject(
          Map("obsWebSocketVersion" -> JsonValue.Str("5.6.3"), "rpcVersion" -> JsonValue.Num(BigDecimal(rpc))) ++
            auth.map("authentication" -> _)
        )
      )
    def identify(): WireMessage =
      val identify = sent.receive()
      assertEquals(identify.op, 1)
      emit(2, JsonObject(Map("negotiatedRpcVersion" -> JsonValue.Num(BigDecimal(1)))))
      identify
    def discover(): Unit =
      val version = sent.receive()
      assertEquals(version.data.string("requestType"), Right("GetVersion"))
      respond(
        version,
        JsonObject(
          Map(
            "availableRequests" -> JsonValue.Arr(Vector("GetVersion", "Echo", "First", "Second").map(JsonValue.Str(_)))
          )
        )
      )
    def respond(request: WireMessage, response: JsonObject = JsonObject.empty, code: Int = 100): Unit =
      emit(
        7,
        JsonObject(
          Map(
            "requestId" -> request.data.fields("requestId"),
            "requestType" -> request.data.fields("requestType"),
            "requestStatus" -> JsonObject(
              Map("result" -> JsonValue.Bool(code == 100), "code" -> JsonValue.Num(BigDecimal(code)))
            ),
            "responseData" -> response
          )
        )
      )
    def event(index: Int): Unit = emit(
      5,
      JsonObject(
        Map(
          "eventType" -> JsonValue.Str("FutureEvent"),
          "eventIntent" -> JsonValue.Num(BigDecimal(1)),
          "eventData" -> JsonObject(Map("index" -> JsonValue.Num(BigDecimal(index))))
        )
      )
    )

  private def connected[A](cfg: ObsConfig = config)(script: Peer => Unit)(
      use: (ObsSession, Peer) => A
  ): Either[ObsError, A] =
    supervised:
      val peer = new Peer
      forkDiscard:
        peer.hello()
        peer.identify().discard
        peer.discover()
        script(peer)
      val result = ObsClient.withTransport(peer, cfg)(session => use(session, peer))
      assert(peer.closed.tryReceive().nonEmpty)
      result

  test("handshake metadata exposes negotiated capabilities"):
    val result = connected()(_ => ()) { (session, _) =>
      assertEquals(session.metadata.availableRequests, Set("GetVersion", "Echo", "First", "Second"))
      assertEquals(session.metadata.negotiatedRpcVersion, 1)
      assertEquals(session.state, Right(ConnectionState.Ready))
    }
    assertEquals(result, Right(()))

  test("raw requests return response data"):
    val result = connected() { peer =>
      peer.respond(peer.sent.receive(), JsonObject(Map("value" -> JsonValue.Str("first"))))
    } { (session, _) =>
      assertEquals(
        session.request(RawRequest("Echo", empty)),
        Right(JsonObject(Map("value" -> JsonValue.Str("first"))))
      )
    }
    assertEquals(result, Right(()))

  test("request rejections preserve OBS status details"):
    val result = connected() { peer =>
      peer.respond(peer.sent.receive(), code = 600)
    } { (session, _) =>
      assert(
        session
          .rawRequest("Rejected")
          .left
          .exists:
            case ObsError.RequestRejected("Rejected", _, 600, None) => true
            case _                                                  => false
      )
    }
    assertEquals(result, Right(()))

  test("raw requests bypass the capability check and let the server decide"):
    val result = connected() { peer =>
      peer.respond(peer.sent.receive())
    } { (session, _) =>
      assertEquals(session.request(RawRequest("Unsupported", empty)), Right(empty))
    }
    assertEquals(result, Right(()))

  test("typed requests absent from the capability set are rejected locally before sending"):
    val result = connected()(_ => ()) { (session, _) =>
      assertEquals(session.request(GetSceneList()), Left(ObsError.UnsupportedRequest("GetSceneList")))
    }
    assertEquals(result, Right(()))

  test("concurrent requests correlate out-of-order and duplicate replies are ignored"):
    val result = connected() { peer =>
      val first = peer.sent.receive()
      val second = peer.sent.receive()
      peer.respond(second, JsonObject(Map("name" -> second.data.fields("requestType"))))
      peer.respond(second)
      peer.respond(first, JsonObject(Map("name" -> first.data.fields("requestType"))))
    } { (session, _) =>
      supervised:
        val one = fork(session.rawRequest("First"))
        val two = fork(session.rawRequest("Second"))
        assertEquals(one.join().flatMap(_.string("name").left.map(SessionWire.malformed)), Right("First"))
        assertEquals(two.join().flatMap(_.string("name").left.map(SessionWire.malformed)), Right("Second"))
    }
    assertEquals(result, Right(()))

  test("timeout removes pending entry; late response cannot complete a later request"):
    val result = connected(config.copy(maxInFlight = 1, requestTimeout = 30.millis)) { peer =>
      val late = peer.sent.receive()
      val next = peer.sent.receive()
      peer.respond(late)
      peer.respond(next)
    } { (session, _) =>
      assertEquals(session.rawRequest("First"), Left(ObsError.Timeout("First")))
      assertEquals(session.rawRequest("Second"), Right(empty))
    }
    assertEquals(result, Right(()))

  test("disconnect completes pending requests with preserved close details"):
    val failure = ObsError.Transport("Peer closed", Some(4011))
    val result = connected() { peer =>
      peer.sent.receive().discard
      peer.inbound.send(Left(failure))
    } { (session, _) => assertEquals(session.rawRequest("Echo"), Left(failure)) }
    assertEquals(result, Right(()))

  test("subscriptions broadcast independently and slow consumer overflow does not block requests"):
    val result = connected(config.copy(subscriptionCapacity = 1)) { peer =>
      val request = peer.sent.receive()
      peer.event(1)
      peer.event(2)
      peer.respond(request)
    } { (session, _) =>
      session.withEvents(): slow =>
        session.withEvents(Set("Unrelated")): filtered =>
          assertEquals(session.rawRequest("Echo"), Right(empty))
          assertEquals(slow.next(), Left(ObsError.Overflow("event subscription")))
          session.close()
          assertEquals(filtered.next(), Left(ObsError.Closed))
    }
    assertEquals(result, Right(Right(Right(()))))

  test("drop newest and oldest are explicit and expose loss counters"):
    for policy <- Vector(OverflowPolicy.DropNewest, OverflowPolicy.DropOldest) do
      val result = connected(config.copy(subscriptionCapacity = 1)) { peer =>
        val request = peer.sent.receive()
        peer.event(1)
        peer.event(2)
        peer.respond(request)
      } { (session, _) =>
        session.withEvents(policy = policy): subscription =>
          assertEquals(session.rawRequest("Echo"), Right(empty))
          assertEquals(subscription.droppedEvents, 1L)
          val expected = if policy == OverflowPolicy.DropNewest then 1 else 2
          assertEquals(
            subscription.next().flatMap(_.eventData.int("index").left.map(SessionWire.malformed)),
            Right(expected)
          )
          session.close()
          // Terminal drop counts survive session failure and close.
          assertEquals(subscription.droppedEvents, 1L)
      }
      assertEquals(result, Right(Right(())))

  test("event flows emit typed terminal errors once"):
    val result = connected()(_ => ()): (session, _) =>
      session.withEvents(): subscription =>
        session.close()
        assertEquals(subscription.flow.runToList(), List(Left(ObsError.Closed)))
    assertEquals(result, Right(Right(())))

  test("a hello requiring authentication without a configured password fails and closes"):
    val auth = JsonObject(Map("salt" -> JsonValue.Str("salt"), "challenge" -> JsonValue.Str("challenge")))
    val peer = new Peer
    peer.hello(Some(auth))
    val result =
      ObsClient.withTransport(peer, config.copy(handshakeTimeout = 20.millis))(_ => fail("Must not reach callback"))
    assert(result.isLeft)
    assert(peer.closed.tryReceive().nonEmpty)

  test("an unsupported RPC version fails and closes"):
    val peer = new Peer
    peer.hello(rpc = 0)
    val result =
      ObsClient.withTransport(peer, config.copy(handshakeTimeout = 20.millis))(_ => fail("Must not reach callback"))
    assert(result.isLeft)
    assert(peer.closed.tryReceive().nonEmpty)

  test("an unexpected handshake message fails and closes"):
    val peer = new Peer
    peer.emit(2, empty)
    val result =
      ObsClient.withTransport(peer, config.copy(handshakeTimeout = 20.millis))(_ => fail("Must not reach callback"))
    assert(result.isLeft)
    assert(peer.closed.tryReceive().nonEmpty)

  test("a missing hello times out and closes"):
    val peer = new Peer
    val result =
      ObsClient.withTransport(peer, config.copy(handshakeTimeout = 20.millis))(_ => fail("Must not reach callback"))
    assert(result.isLeft)
    assert(peer.closed.tryReceive().nonEmpty)

  test("password provider authentication and redaction"):
    supervised:
      val peer = new Peer
      val password = "secret-秘密"
      forkDiscard:
        peer.hello(Some(JsonObject(Map("salt" -> JsonValue.Str("salt"), "challenge" -> JsonValue.Str("challenge")))))
        val identify = peer.identify()
        assertEquals(
          identify.data.string("authentication"),
          Right(Authentication.compute(password, "salt", "challenge"))
        )
        peer.discover()
      val cfg = config.copy(passwordProvider = PasswordProvider.fixed(Some(password)))
      assert(!cfg.toString.contains(password))
      assert(!cfg.passwordProvider.toString.contains(password))
      assert(!cfg.toString.contains(cfg.uri))
      assertEquals(ObsClient.withTransport(peer, cfg)(_ => ()), Right(()))

  test("callback defects close the transport before returning"):
    intercept[IllegalStateException]:
      connected()(_ => ())((_, _) => throw new IllegalStateException("callback defect")).discard

  test("http URIs are rejected for the WebSocket client"):
    assert(config.copy(uri = "http://localhost").validate.isLeft)

  test("URIs with embedded credentials are rejected"):
    assert(config.copy(uri = "ws://a:b@localhost").validate.isLeft)

  test("unparseable URIs are rejected"):
    assert(config.copy(uri = "not a uri").validate.isLeft)

  test("zero in-flight capacity is rejected"):
    assert(config.copy(maxInFlight = 0).validate.isLeft)

  test("zero request timeout is rejected"):
    assert(config.copy(requestTimeout = Duration.Zero).validate.isLeft)

  test("the default test configuration is valid"):
    assert(config.validate.isRight)

  test("subscription masks validate bounds and expose presets"):
    assertEquals(
      EventSubscriptions.fromLong(-1),
      Left(ObsError.InvalidConfiguration("Event subscription mask must be nonnegative"))
    )
    assertEquals(EventSubscriptions.fromLong(0), Right(EventSubscriptions.none))
    assertEquals(
      EventSubscriptions.normal.value,
      com.worxbend.obs.websocket.client.protocol.enums.EventSubscription.All.value
    )

  test("the connection state table permits and rejects explicit transitions"):
    assert(ConnectionState.transition(ConnectionState.Connecting, ConnectionState.AwaitingHello).isRight)
    assert(ConnectionState.transition(ConnectionState.Ready, ConnectionState.Closing).isRight)
    assert(ConnectionState.transition(ConnectionState.Closing, ConnectionState.Closed).isRight)
    assertEquals(
      ConnectionState.transition(ConnectionState.Closed, ConnectionState.Ready),
      Left(ObsError.InternalError("Invalid state transition: Closed -> Ready"))
    )

  private def batchItem(name: String, code: Int = 100): JsonObject = JsonObject(
    Map(
      "requestType" -> JsonValue.Str(name),
      "requestStatus" -> JsonObject(
        Map("result" -> JsonValue.Bool(code == 100), "code" -> JsonValue.Num(BigDecimal(code)))
      )
    )
  )

  test("typed heterogeneous batches preserve static result types and ordering"):
    val result = connected() { peer =>
      val request = peer.sent.receive()
      assertEquals(request.op, 8)
      assertEquals(request.data.int("executionType"), Right(1))
      peer.emit(
        9,
        JsonObject(
          Map(
            "requestId" -> request.data.fields("requestId"),
            "results" ->
              JsonValue.Arr(Vector(batchItem("First"), batchItem("Second", 600)))
          )
        )
      )
    } { (session, _) =>
      val second = new Request[String]:
        def requestType: String = "Second"
        def requestData: JsonObject = empty
        def decodeResponse(data: JsonObject): Either[ProtocolError, String] = data.string("value")
      val result = session.typedBatch(
        (BatchCall(RawRequest("First", empty)), BatchCall(second)),
        BatchExecution.SerialFrame
      )
      val typed: Either[ObsError, (TypedBatchResult[JsonObject], TypedBatchResult[String])] = result
      assert(typed.exists:
        case (
              TypedBatchResult.Completed(Right(value)),
              TypedBatchResult.Completed(Left(ObsError.RequestRejected("Second", _, 600, None)))
            ) =>
          value == empty
        case _ => false)
      assertEquals(session.typedBatch(EmptyTuple), Right(EmptyTuple))
    }
    assertEquals(result, Right(()))

  test("halted batches mark unexecuted entries and reject impossible result sequences"):
    val cases = Vector(
      Vector(batchItem("First", 600)) -> true,
      Vector.empty -> false,
      Vector(batchItem("First")) -> false,
      Vector(batchItem("First", 600), batchItem("Second")) -> false,
      Vector(batchItem("Wrong")) -> false,
      Vector(JsonValue.Str("bad")) -> false,
      Vector(batchItem("First"), batchItem("Second"), batchItem("Third")) -> false
    )
    for (results, valid) <- cases do
      val result = connected() { peer =>
        val request = peer.sent.receive()
        peer.emit(
          9,
          JsonObject(Map("requestId" -> request.data.fields("requestId"), "results" -> JsonValue.Arr(results)))
        )
      } { (session, _) =>
        val result = session.typedBatch(
          (BatchCall(RawRequest("First", empty)), BatchCall(RawRequest("Second", empty))),
          BatchExecution.SerialFrame,
          BatchFailurePolicy.Halt
        )
        assertEquals(result.isRight, valid)
        if valid then assert(result.exists(_._2 == TypedBatchResult.NotExecuted))
      }
      assertEquals(result, Right(()))

  test("batch disconnect reports ambiguous execution outcome"):
    val failure = ObsError.Transport("lost")
    val result = connected() { peer =>
      peer.sent.receive().discard
      peer.inbound.send(Left(failure))
    } { (session, _) =>
      assertEquals(session.batch(Vector(RawRequest("Echo", empty))), Left(ObsError.AmbiguousBatchOutcome(failure)))
    }
    assertEquals(result, Right(()))

  test("in-flight bounds reject saturation and cancellation releases capacity"):
    val observed = Channel.buffered[Unit](1)
    val release = Channel.buffered[Unit](1)
    val result = connected(config.copy(maxInFlight = 1)) { peer =>
      peer.sent.receive().discard
      observed.send(())
      release.receive()
      peer.respond(peer.sent.receive())
    } { (session, _) =>
      supervised:
        val pending = forkCancellable(session.rawRequest("First"))
        observed.receive()
        assertEquals(session.rawRequest("Second"), Left(ObsError.Overflow("in-flight requests")))
        assertEquals(session.batch(Vector(RawRequest("Second", empty))), Left(ObsError.Overflow("in-flight requests")))
        pending.cancel().discard
        release.send(())
        assertEquals(session.rawRequest("Second"), Right(empty))
    }
    assertEquals(result, Right(()))

  test("reidentify acknowledgement preserves the session and closed or escaped sessions reject operations"):
    val escaped = connected() { peer =>
      val reidentify = peer.sent.receive()
      assertEquals(reidentify.op, 3)
      assertEquals(reidentify.data.int("eventSubscriptions"), Right(0))
      peer.emit(2, JsonObject(Map("negotiatedRpcVersion" -> JsonValue.Num(BigDecimal(1)))))
      peer.respond(peer.sent.receive())
    } { (session, _) =>
      assertEquals(session.reidentify(EventSubscriptions.none), Right(()))
      assertEquals(session.rawRequest("Echo"), Right(empty))
      session.close()
      session.close()
      assertEquals(session.state, Right(ConnectionState.Closed))
      assertEquals(session.rawRequest("Echo"), Left(ObsError.Closed))
      assertEquals(session.reidentify(EventSubscriptions.normal), Left(ObsError.Closed))
      assertEquals(session.withEvents()(_ => ()), Left(ObsError.Closed))
      session
    }.toOption.get
    assertEquals(escaped.rawRequest("Echo"), Left(ObsError.Closed))
    escaped.close()

  test("malformed frames fail pending requests without exposing frame contents"):
    val result = connected() { peer =>
      peer.sent.receive().discard
      peer.inbound.send(Right("secret invalid frame"))
    } { (session, _) =>
      val result = session.rawRequest("Echo")
      assert(result.left.exists(_.isInstanceOf[ObsError.MalformedPayload]))
      assert(!result.toString.contains("secret"))
    }
    assertEquals(result, Right(()))

  test("transport send failure and malformed version capabilities fail startup"):
    val broken = new Peer:
      override def send(text: String): Either[ObsError, Unit] =
        assert(text.nonEmpty)
        Left(ObsError.Transport("Send failed"))
    broken.hello()
    assertEquals(ObsClient.withTransport(broken)(_ => ()), Left(ObsError.Transport("Send failed")))
    assert(broken.closed.tryReceive().nonEmpty)
    for capabilities <- Vector(
        empty,
        JsonObject(Map("availableRequests" -> JsonValue.Arr(Vector(JsonValue.Num(BigDecimal(1))))))
      )
    do
      supervised:
        val peer = new Peer
        forkDiscard:
          peer.hello()
          peer.identify().discard
          peer.respond(peer.sent.receive(), capabilities)
        assert(ObsClient.withTransport(peer, config)(_ => ()).left.exists(_.isInstanceOf[ObsError.MalformedPayload]))

  test("authentication rejection preserves its WebSocket close code"):
    val peer = new Peer
    peer.inbound.send(Left(ObsError.Transport("Authentication failed", Some(4009))))
    assertEquals(
      ObsClient.withTransport(peer, config)(_ => ()),
      Left(ObsError.Authentication("Server rejected authentication", Some(4009)))
    )

  test("close code 4009 after identification keeps its transport classification"):
    val failure = ObsError.Transport("WebSocket closed", Some(4009))
    val result = connected() { peer =>
      peer.sent.receive().discard
      peer.inbound.send(Left(failure))
    } { (session, _) => assertEquals(session.rawRequest("Echo"), Left(failure)) }
    assertEquals(result, Right(()))

  test("oversized outbound payloads fail only the offending request or batch"):
    val oversized = ObsError.MessageTooLarge("Outgoing message exceeds configured byte limit")
    val peer = new Peer:
      override def send(text: String): Either[ObsError, Unit] =
        if text.length > 512 then Left(oversized) else super.send(text)
    supervised:
      forkDiscard:
        peer.hello()
        peer.identify().discard
        peer.discover()
        peer.respond(peer.sent.receive())
      val big = "x" * 4096
      val result = ObsClient.withTransport(peer, config): session =>
        assertEquals(session.rawRequest("Echo", JsonObject(Map("blob" -> JsonValue.Str(big)))), Left(oversized))
        assertEquals(session.batch(Vector(RawRequest(s"E$big"))), Left(oversized))
        assertEquals(session.state, Right(ConnectionState.Ready))
        assertEquals(session.rawRequest("Echo"), Right(empty))
      assertEquals(result, Right(()))
      assert(peer.closed.tryReceive().nonEmpty)

  test("a throwing transport receive fails the session instead of stalling"):
    val peer = new Peer:
      override def receive(): Either[ObsError, String] = throw new IllegalStateException("reader defect")
    assertEquals(
      ObsClient.withTransport(peer, config)(_ => ()),
      Left(ObsError.Transport("Transport receive failed unexpectedly"))
    )
    assert(peer.closed.tryReceive().nonEmpty)

  test("a throwing transport send fails the session instead of stalling"):
    val peer = new Peer:
      override def send(text: String): Either[ObsError, Unit] = throw new IllegalStateException("writer defect")
    peer.hello()
    assertEquals(
      ObsClient.withTransport(peer, config)(_ => ()),
      Left(ObsError.Transport("Transport send failed unexpectedly"))
    )
    assert(peer.closed.tryReceive().nonEmpty)

  test("typed request and batch decoder failures remain typed"):
    val request = new Request[String]:
      def requestType: String = "Echo"
      def requestData: JsonObject = empty
      def decodeResponse(data: JsonObject): Either[ProtocolError, String] = data.string("required")
    val result = connected() { peer =>
      peer.respond(peer.sent.receive())
      val batch = peer.sent.receive()
      peer.emit(
        9,
        JsonObject(
          Map("requestId" -> batch.data.fields("requestId"), "results" -> JsonValue.Arr(Vector(batchItem("Echo"))))
        )
      )
      val malformedBatch = peer.sent.receive()
      peer.emit(9, JsonObject(Map("requestId" -> malformedBatch.data.fields("requestId"))))
    } { (session, _) =>
      assert(session.request(request).left.exists(_.isInstanceOf[ObsError.MalformedPayload]))
      assert(
        session
          .typedBatch(Tuple1(BatchCall(request)))
          .exists(_.head match
            case TypedBatchResult.Completed(Left(_: ObsError.MalformedPayload)) => true
            case _                                                              => false)
      )
      assert(session.batch(Vector(request)).left.exists(_.isInstanceOf[ObsError.MalformedPayload]))
    }
    assertEquals(result, Right(()))

  test("invalid typed request fields are rejected before sending"):
    val invalid = com.worxbend.obs.websocket.client.protocol.requests.SetCurrentProgramScene(sceneName = Field.Null)
    val result = connected()(_ => ()) { (session, _) =>
      // Typed batch entries are capability-checked exactly like single typed requests.
      assertEquals(session.batch(Vector(GetSceneList())), Left(ObsError.UnsupportedRequest("GetSceneList")))
      supervised:
        val allowed = new ObsSession(
          session.metadata.copy(availableRequests = Set(invalid.requestType)),
          config,
          SessionDependencies.live,
          ox.channels.Actor.create(
            new SessionLogic(
              config,
              None,
              Channel.buffered[WireMessage](1),
              Channel.buffered[Either[ObsError, ConnectionMetadata]](1)
            )
          )
        )
        assert(allowed.request(invalid).left.exists(_.isInstanceOf[ObsError.MalformedPayload]))
        assert(allowed.batch(Vector(invalid)).left.exists(_.isInstanceOf[ObsError.MalformedPayload]))
    }
    assertEquals(result, Right(()))

  test("raw event subscriptions preserve new fields of known events"):
    val result = connected() { peer =>
      val request = peer.sent.receive()
      peer.emit(
        5,
        JsonObject(
          Map(
            "eventType" -> JsonValue.Str("CurrentProgramSceneChanged"),
            "eventIntent" -> JsonValue.Num(BigDecimal(4)),
            "eventData" -> JsonObject(
              Map(
                "sceneName" -> JsonValue.Str("Scene"),
                "sceneUuid" -> JsonValue.Str("uuid"),
                "futureField" -> JsonValue.Str("preserved")
              )
            )
          )
        )
      )
      peer.respond(request)
    } { (session, _) =>
      session.withRawEvents() { events =>
        assertEquals(session.rawRequest("Echo"), Right(empty))
        val delivered = events.next()
        assert(delivered.exists:
          case UnknownEvent("CurrentProgramSceneChanged", _) => true
          case _                                             => false)
        assertEquals(
          delivered.flatMap(_.eventData.string("futureField").left.map(SessionWire.malformed)),
          Right("preserved")
        )
      }
    }
    assertEquals(result, Right(Right(())))

  test("parallel batches fail locally before sending any application request"):
    val result = connected()(_ => ()) { (session, peer) =>
      for policy <- BatchFailurePolicy.values do
        assert(
          session
            .batch(Vector(RawRequest("Echo")), BatchExecution.Parallel, policy)
            .left
            .exists(_.isInstanceOf[ObsError.InvalidConfiguration])
        )
      assert(session.typedBatch(Tuple1(BatchCall(RawRequest("Echo"))), BatchExecution.Parallel).isLeft)
      assertEquals(peer.sent.tryReceive(), None)
    }
    assertEquals(result, Right(()))

  test("explicit WebSocket ports must be within the TCP range"):
    for port <- Vector(0, 65536) do assert(config.copy(uri = s"ws://localhost:$port").validate.isLeft)
    for uri <- Vector("ws://localhost", "ws://localhost:1", "ws://localhost:65535") do
      assert(config.copy(uri = uri).validate.isRight)

  test("configuration rendering redacts invalid userinfo and query credentials"):
    for uri <- Vector("ws://user:secret@localhost", "ws://localhost?token=secret") do
      assert(!config.copy(uri = uri).toString.contains("secret"))
