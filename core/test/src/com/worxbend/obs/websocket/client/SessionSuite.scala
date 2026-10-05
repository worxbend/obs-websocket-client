package com.worxbend.obs.websocket.client

import com.worxbend.obs.websocket.client.protocol.*
import com.worxbend.obs.websocket.client.protocol.requests.GetSceneList
import munit.FunSuite
import ox.*
import ox.channels.{Actor, Channel, ChannelClosed}
import scala.concurrent.duration.*

class SessionSuite extends FunSuite:
  private val inFlightLimitResource: String     = "in-flight requests"
  private val closedWebSocketMessage: String    = "WebSocket closed"
  private val interruptedRegistrationId: String = "interrupted-registration"
  private val httpEndpoint: String              = "http://localhost"
  private val cleanupDefectMessage: String      = "cleanup defect"
  private val unexpectedCallbackMessage: String = "Must not reach callback"

  private val empty = JsonObject.empty
  // Production-default budgets: fork scheduling under instrumented, saturated machines needs headroom.
  // Only tests that exercise deadline behavior tighten budgets explicitly via config.copy.
  private val config = ObsConfig()

  private class Peer extends ObsTransport:
    val inbound                                    = Channel.buffered[Either[ObsError, String]](64)
    val sent                                       = Channel.buffered[WireMessage](64)
    val closed                                     = Channel.buffered[Unit](1)
    def send(text: String): Either[ObsError, Unit] =
      Protocol.decode(text = text).left.map(SessionWire.malformed).map(sent.send)
    def receive(): Either[ObsError, String] = inbound.receiveOrClosed() match
      case Right(text: String)   => Right(text)
      case Left(error: ObsError) => Left(error)
      case _: ChannelClosed      => Left(ObsError.Closed)
    def close(): Unit =
      closed.trySendOrClosed(()).discard
      inbound.doneOrClosed().discard
    def emit(op: Int, data: JsonObject): Unit =
      inbound.send(Right(Protocol.encode(message = WireMessage(op = op, data = data))))
    def hello(auth: Option[JsonObject] = None, rpc: Int = 1): Unit =
      emit(
        op   = 0,
        data = JsonObject(
          fields = Map(
            "obsWebSocketVersion" -> JsonValue.Str(value = "5.6.3"),
            "rpcVersion"          -> JsonValue.Num(value = BigDecimal(rpc)),
          ) ++
            auth.map("authentication" -> _)
        ),
      )
    def identify(): WireMessage =
      val identify = sent.receive()
      assertEquals(identify.op, 1)
      emit(op = 2, data = JsonObject(fields = Map("negotiatedRpcVersion" -> JsonValue.Num(value = BigDecimal(1)))))
      identify
    def discover(): Unit =
      val version = sent.receive()
      assertEquals(version.data.string(name = "requestType"), Right("GetVersion"))
      respond(
        request  = version,
        response = JsonObject(
          fields = Map(
            "availableRequests" -> JsonValue.Arr(value =
              Vector("GetVersion", "Echo", "First", "Second").map(JsonValue.Str(_))
            )
          )
        ),
      )
    def respond(request: WireMessage, response: JsonObject = JsonObject.empty, code: Int = 100): Unit =
      emit(
        op   = 7,
        data = JsonObject(
          fields = Map(
            "requestId"     -> request.data.fields("requestId"),
            "requestType"   -> request.data.fields("requestType"),
            "requestStatus" -> JsonObject(
              fields =
                Map("result" -> JsonValue.Bool(value = code == 100), "code" -> JsonValue.Num(value = BigDecimal(code)))
            ),
            "responseData" -> response,
          )
        ),
      )
    def event(index: Int): Unit = emit(
      op   = 5,
      data = JsonObject(
        fields = Map(
          "eventType"   -> JsonValue.Str(value = "FutureEvent"),
          "eventIntent" -> JsonValue.Num(value = BigDecimal(1)),
          "eventData"   -> JsonObject(fields = Map("index" -> JsonValue.Num(value = BigDecimal(index)))),
        )
      ),
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
      val result = ObsClient.withTransport(transport = peer, config = cfg)(session => use(session, peer))
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
      peer.respond(
        request  = peer.sent.receive(),
        response = JsonObject(fields = Map("value" -> JsonValue.Str(value = "first"))),
      )
    } { (session, _) =>
      assertEquals(
        session.request(request = RawRequest(requestType = "Echo", requestData = empty)),
        Right(JsonObject(fields = Map("value" -> JsonValue.Str(value = "first")))),
      )
    }
    assertEquals(result, Right(()))

  test("request rejections preserve OBS status details"):
    val result = connected() { peer =>
      peer.respond(request = peer.sent.receive(), code = 600)
    } { (session, _) =>
      assert(
        session
          .rawRequest(requestType = "Rejected")
          .left
          .exists:
            case ObsError.RequestRejected("Rejected", _, 600, None) => true
            case _                                                  => false
      )
    }
    assertEquals(result, Right(()))

  test("raw requests bypass the capability check and let the server decide"):
    val result = connected() { peer =>
      peer.respond(request = peer.sent.receive())
    } { (session, _) =>
      assertEquals(
        session.request(request = RawRequest(requestType = "Unsupported", requestData = empty)),
        Right(empty),
      )
    }
    assertEquals(result, Right(()))

  test("typed requests absent from the capability set are rejected locally before sending"):
    val result = connected()(_ => ()) { (session, _) =>
      assertEquals(
        session.request(request = GetSceneList()),
        Left(ObsError.UnsupportedRequest(requestType = "GetSceneList")),
      )
    }
    assertEquals(result, Right(()))

  test("concurrent requests correlate out-of-order and duplicate replies are ignored"):
    val result = connected() { peer =>
      val first  = peer.sent.receive()
      val second = peer.sent.receive()
      peer.respond(request = second, response = JsonObject(fields = Map("name" -> second.data.fields("requestType"))))
      peer.respond(request = second)
      peer.respond(request = first, response  = JsonObject(fields = Map("name" -> first.data.fields("requestType"))))
    } { (session, _) =>
      supervised:
        val one = fork(session.rawRequest(requestType = "First"))
        val two = fork(session.rawRequest(requestType = "Second"))
        assertEquals(one.join().flatMap(_.string(name = "name").left.map(SessionWire.malformed)), Right("First"))
        assertEquals(two.join().flatMap(_.string(name = "name").left.map(SessionWire.malformed)), Right("Second"))
    }
    assertEquals(result, Right(()))

  test("timeout removes pending entry; late response cannot complete a later request"):
    val result = connected(cfg = config.copy(maxInFlight = 1, requestTimeout = 30.millis)) { peer =>
      val late = peer.sent.receive()
      val next = peer.sent.receive()
      peer.respond(request = late)
      peer.respond(request = next)
    } { (session, _) =>
      assertEquals(session.rawRequest(requestType = "First"), Left(ObsError.Timeout(operation = "First")))
      assertEquals(session.rawRequest(requestType = "Second"), Right(empty))
    }
    assertEquals(result, Right(()))

  test("disconnect completes pending requests with preserved close details"):
    val failure = ObsError.Transport(message = "Peer closed", closeCode = Some(4011))
    val result  = connected() { peer =>
      peer.sent.receive().discard
      peer.inbound.send(Left(failure))
    } { (session, _) => assertEquals(session.rawRequest(requestType = "Echo"), Left(failure)) }
    assertEquals(result, Right(()))

  test("subscriptions broadcast independently and slow consumer overflow does not block requests"):
    val result = connected(cfg = config.copy(subscriptionCapacity = 1)) { peer =>
      val request     = peer.sent.receive()
      peer.event(index     = 1)
      peer.event(index     = 2)
      peer.respond(request = request)
    } { (session, _) =>
      session.withEvents(): slow =>
        session.withEvents(eventTypes = Set("Unrelated")): filtered =>
          assertEquals(session.rawRequest(requestType = "Echo"), Right(empty))
          assertEquals(slow.next(), Next.Failed(error = ObsError.Overflow(resource = "event subscription")))
          session.close()
          assertEquals(filtered.next(), Next.Ended)
    }
    assertEquals(result, Right(Right(Right(()))))

  test("drop newest and oldest are explicit and expose loss counters"):
    for policy <- Vector(OverflowPolicy.DropNewest, OverflowPolicy.DropOldest) do
      val result = connected(cfg = config.copy(subscriptionCapacity = 1)) { peer =>
        val request     = peer.sent.receive()
        peer.event(index     = 1)
        peer.event(index     = 2)
        peer.respond(request = request)
      } { (session, _) =>
        session.withEvents(policy = policy): subscription =>
          assertEquals(session.rawRequest(requestType = "Echo"), Right(empty))
          assertEquals(subscription.droppedEvents, 1L)
          val expected = if policy == OverflowPolicy.DropNewest then 1 else 2
          subscription.next() match
            case Next.Item(event) =>
              assertEquals(event.eventData.int(name = "index").left.map(SessionWire.malformed), Right(expected))
            case other => fail(s"Expected an event, got $other")
          session.close()
          // Terminal drop counts survive session failure and close.
          assertEquals(subscription.droppedEvents, 1L)
      }
      assertEquals(result, Right(Right(())))

  test("event flows complete cleanly when the session closes"):
    val result = connected()(_ => ()): (session, _) =>
      session.withEvents(): subscription =>
        session.close()
        assertEquals(subscription.flow.runToList(), List.empty)
    assertEquals(result, Right(Right(())))

  test("a hello requiring authentication without a configured password fails and closes"):
    val auth = JsonObject(fields =
      Map("salt" -> JsonValue.Str(value = "salt"), "challenge" -> JsonValue.Str(value = "challenge"))
    )
    val peer   = new Peer
    peer.hello(auth = Some(auth))
    val result =
      ObsClient.withTransport(transport = peer, config = config.copy(handshakeTimeout = 20.millis))(_ =>
        fail(unexpectedCallbackMessage)
      )
    assert(result.isLeft)
    assert(peer.closed.tryReceive().nonEmpty)

  test("an unsupported RPC version fails and closes"):
    val peer   = new Peer
    peer.hello(rpc = 0)
    val result =
      ObsClient.withTransport(transport = peer, config = config.copy(handshakeTimeout = 20.millis))(_ =>
        fail(unexpectedCallbackMessage)
      )
    assert(result.isLeft)
    assert(peer.closed.tryReceive().nonEmpty)

  test("an unexpected handshake message fails and closes"):
    val peer   = new Peer
    peer.emit(op = 2, data = empty)
    val result =
      ObsClient.withTransport(transport = peer, config = config.copy(handshakeTimeout = 20.millis))(_ =>
        fail(unexpectedCallbackMessage)
      )
    assert(result.isLeft)
    assert(peer.closed.tryReceive().nonEmpty)

  test("a missing hello times out and closes"):
    val peer   = new Peer
    val result =
      ObsClient.withTransport(transport = peer, config = config.copy(handshakeTimeout = 20.millis))(_ =>
        fail(unexpectedCallbackMessage)
      )
    assert(result.isLeft)
    assert(peer.closed.tryReceive().nonEmpty)

  test("password provider authentication and redaction"):
    supervised:
      val peer     = new Peer
      val password = "secret-秘密"
      forkDiscard:
        peer.hello(auth =
          Some(
            JsonObject(fields =
              Map("salt" -> JsonValue.Str(value = "salt"), "challenge" -> JsonValue.Str(value = "challenge"))
            )
          )
        )
        val identify = peer.identify()
        assertEquals(
          identify.data.string(name = "authentication"),
          Right(
            Authentication.compute(
              password  = password.getBytes(java.nio.charset.StandardCharsets.UTF_8),
              salt      = "salt",
              challenge = "challenge",
            )
          ),
        )
        peer.discover()
      val cfg = config.copy(passwordProvider = PasswordProvider.fixed(value = Some(password)))
      assert(!cfg.toString.contains(password))
      assert(!cfg.passwordProvider.toString.contains(password))
      // Validation rejects credential-carrying URIs, so the URI itself is not secret and renders.
      assert(cfg.toString.contains(cfg.uri))
      assertEquals(ObsClient.withTransport(transport = peer, config = cfg)(_ => ()), Right(()))

  test("callback defects close the transport before returning"):
    intercept[IllegalStateException]:
      connected()(_ => ())((_, _) => throw new IllegalStateException("callback defect")).discard

  test("http URIs are rejected for the WebSocket client"):
    assert(config.copy(uri = httpEndpoint).validate.isLeft)

  test("URIs with embedded credentials are rejected"):
    assert(config.copy(uri = "ws://a:b@localhost").validate.isLeft)

  test("URIs with a query string are rejected so secrets cannot leak into requests or logs"):
    assertEquals(
      config.copy(uri = "ws://localhost/?token=secret").validate,
      Left(
        ObsError.InvalidConfiguration(message = "Expected ws/wss URI with host, without credentials, query or fragment")
      ),
    )

  test("unparseable URIs are rejected"):
    assert(config.copy(uri = "not a uri").validate.isLeft)

  test("plaintext credential exposure requires a password, the ws scheme and a remote host"):
    val password = Some("synthetic-test-password")
    assert(config.copy(uri = "ws://obs.internal.example:4455").plaintextCredentialsExposed(password = password))
    assert(!config.copy(uri = "ws://obs.internal.example:4455").plaintextCredentialsExposed(password = None))
    assert(!config.copy(uri = "wss://obs.internal.example:4455").plaintextCredentialsExposed(password = password))
    assert(!config.plaintextCredentialsExposed(password = password))
    assert(!config.copy(uri = "ws:opaque").plaintextCredentialsExposed(password = password))
    assert(!config.copy(uri = "not a uri").plaintextCredentialsExposed(password = password))

  test("loopback recognition covers localhost, 127.0.0.0/8 and ::1 without DNS resolution"):
    val loopback = Vector("localhost", "LOCALHOST", "127.0.0.1", "127.255.255.254", "::1", "[::1]")
    val remote   = Vector("example.com", "127.evil.example.com", "10.0.0.1", "127.0.0.256", "127..0.1")
    loopback.foreach(host => assert(ObsConfig.isLoopbackHost(host = host), s"$host must be loopback"))
    remote.foreach(host => assert(!ObsConfig.isLoopbackHost(host = host), s"$host must be remote"))

  test("zero in-flight capacity is rejected"):
    assert(config.copy(maxInFlight = 0).validate.isLeft)

  test("zero request timeout is rejected"):
    assert(config.copy(requestTimeout = Duration.Zero).validate.isLeft)

  test("the default test configuration is valid"):
    assert(config.validate.isRight)

  test("subscription masks validate bounds and expose presets"):
    assertEquals(
      EventSubscriptions.fromLong(value = -1),
      Left(ObsError.InvalidConfiguration(message = "Event subscription mask must be nonnegative")),
    )
    assertEquals(EventSubscriptions.fromLong(value = 0), Right(EventSubscriptions.none))
    assertEquals(
      EventSubscriptions.normal.value,
      com.worxbend.obs.websocket.client.protocol.enums.EventSubscription.All.value,
    )

  test("the connection state table permits and rejects explicit transitions"):
    assert(ConnectionState.transition(from = ConnectionState.Connecting, to = ConnectionState.AwaitingHello).isRight)
    assert(ConnectionState.transition(from = ConnectionState.Ready, to = ConnectionState.Closing).isRight)
    assert(ConnectionState.transition(from = ConnectionState.Closing, to = ConnectionState.Closed).isRight)
    assertEquals(
      ConnectionState.transition(from = ConnectionState.Closed, to = ConnectionState.Ready),
      Left(ObsError.InternalError(message = "Invalid state transition: Closed -> Ready")),
    )

  private def batchItem(name: String, code: Int = 100): JsonObject = JsonObject(
    fields = Map(
      "requestType"   -> JsonValue.Str(value = name),
      "requestStatus" -> JsonObject(
        fields = Map("result" -> JsonValue.Bool(value = code == 100), "code" -> JsonValue.Num(value = BigDecimal(code)))
      ),
    )
  )

  test("typed heterogeneous batches preserve static result types and ordering"):
    val result = connected() { peer =>
      val request = peer.sent.receive()
      assertEquals(request.op, 8)
      assertEquals(request.data.int(name = "executionType"), Right(1))
      peer.emit(
        op   = 9,
        data = JsonObject(
          fields = Map(
            "requestId" -> request.data.fields("requestId"),
            "results"   ->
              JsonValue.Arr(value = Vector(batchItem(name = "First"), batchItem(name = "Second", code = 600))),
          )
        ),
      )
    } { (session, _) =>
      val second = new Request[String]:
        def requestType: String                                             = "Second"
        def requestData: JsonObject                                         = empty
        def decodeResponse(data: JsonObject): Either[ProtocolError, String] = data.string(name = "value")
      val result = session.typedBatch(
        calls =
          (BatchCall(request = RawRequest(requestType = "First", requestData = empty)), BatchCall(request = second)),
        execution = BatchExecution.SerialFrame,
      )
      val typed: Either[ObsError, (TypedBatchResult[JsonObject], TypedBatchResult[String])] = result
      assert(typed.exists:
        case (
              TypedBatchResult.Completed(Right(value)),
              TypedBatchResult.Completed(Left(ObsError.RequestRejected("Second", _, 600, None))),
            ) =>
          value == empty
        case _ => false)
      assertEquals(session.typedBatch(calls = EmptyTuple), Right(EmptyTuple))
    }
    assertEquals(result, Right(()))

  test("halted batches mark unexecuted entries and reject impossible result sequences"):
    val cases = Vector(
      Vector(batchItem(name = "First", code = 600))                                            -> true,
      Vector.empty                                                                             -> false,
      Vector(batchItem(name = "First"))                                                        -> false,
      Vector(batchItem(name = "First", code = 600), batchItem(name = "Second"))                -> false,
      Vector(batchItem(name = "Wrong"))                                                        -> false,
      Vector(JsonValue.Str(value = "bad"))                                                     -> false,
      Vector(batchItem(name = "First"), batchItem(name = "Second"), batchItem(name = "Third")) -> false,
    )
    for (results, valid) <- cases do
      val result = connected() { peer =>
        val request = peer.sent.receive()
        peer.emit(
          op   = 9,
          data = JsonObject(fields =
            Map("requestId" -> request.data.fields("requestId"), "results" -> JsonValue.Arr(value = results))
          ),
        )
      } { (session, _) =>
        val result = session.typedBatch(
          calls = (
            BatchCall(request = RawRequest(requestType = "First", requestData = empty)),
            BatchCall(request = RawRequest(requestType = "Second", requestData = empty)),
          ),
          execution     = BatchExecution.SerialFrame,
          failurePolicy = BatchFailurePolicy.Halt,
        )
        assertEquals(result.isRight, valid)
        if valid then assert(result.exists(_._2 == TypedBatchResult.NotExecuted))
      }
      assertEquals(result, Right(()))

  test("batch disconnect reports ambiguous execution outcome"):
    val failure = ObsError.Transport(message = "lost")
    val result  = connected() { peer =>
      peer.sent.receive().discard
      peer.inbound.send(Left(failure))
    } { (session, _) =>
      assertEquals(
        session.batch(requests = Vector(RawRequest(requestType = "Echo", requestData = empty))),
        Left(ObsError.AmbiguousBatchOutcome(cause = failure)),
      )
    }
    assertEquals(result, Right(()))

  test("in-flight bounds reject saturation and cancellation releases capacity"):
    val observed = Channel.buffered[Unit](1)
    val release  = Channel.buffered[Unit](1)
    val result   = connected(cfg = config.copy(maxInFlight = 1)) { peer =>
      peer.sent.receive().discard
      observed.send(())
      release.receive()
      peer.respond(request = peer.sent.receive())
    } { (session, _) =>
      supervised:
        val pending = forkCancellable(session.rawRequest(requestType = "First"))
        observed.receive()
        assertEquals(
          session.rawRequest(requestType = "Second"),
          Left(ObsError.Overflow(resource = inFlightLimitResource)),
        )
        assertEquals(
          session.batch(requests = Vector(RawRequest(requestType = "Second", requestData = empty))),
          Left(ObsError.Overflow(resource = inFlightLimitResource)),
        )
        pending.cancel().discard
        release.send(())
        assertEquals(session.rawRequest(requestType = "Second"), Right(empty))
    }
    assertEquals(result, Right(()))

  test("reidentify acknowledgement preserves the session and closed or escaped sessions reject operations"):
    val escaped = connected() { peer =>
      val reidentify = peer.sent.receive()
      assertEquals(reidentify.op, 3)
      assertEquals(reidentify.data.int(name = "eventSubscriptions"), Right(0))
      peer.emit(op = 2, data = JsonObject(fields = Map("negotiatedRpcVersion" -> JsonValue.Num(value = BigDecimal(1)))))
      peer.respond(request = peer.sent.receive())
    } { (session, _) =>
      assertEquals(session.reidentify(subscriptions = EventSubscriptions.none), Right(()))
      assertEquals(session.rawRequest(requestType = "Echo"), Right(empty))
      // The peer acknowledged the reidentify before responding, so the backlog observed by the hook has drained.
      assertEquals(session.pendingReidentifyAcks, Right(0))
      session.close()
      session.close()
      assertEquals(session.state, Right(ConnectionState.Closed))
      assertEquals(session.rawRequest(requestType = "Echo"), Left(ObsError.Closed))
      // Registration fails before anything is queued, so the batch outcome is certain, not ambiguous.
      assertEquals(
        session.batch(requests = Vector(RawRequest(requestType = "Echo", requestData = empty))),
        Left(ObsError.Closed),
      )
      assertEquals(session.reidentify(subscriptions = EventSubscriptions.normal), Left(ObsError.Closed))
      assertEquals(session.withEvents()(_ => ()), Left(ObsError.Closed))
      session
    }.toOption.get
    assertEquals(escaped.rawRequest(requestType = "Echo"), Left(ObsError.Closed))
    escaped.close()

  test("malformed frames fail pending requests without exposing frame contents"):
    val result = connected() { peer =>
      peer.sent.receive().discard
      peer.inbound.send(Right("secret invalid frame"))
    } { (session, _) =>
      val result = session.rawRequest(requestType = "Echo")
      assert(result.left.exists(_.isInstanceOf[ObsError.MalformedPayload]))
      assert(!result.toString.contains("secret"))
    }
    assertEquals(result, Right(()))

  test("transport send failure and malformed version capabilities fail startup"):
    val broken = new Peer:
      override def send(text: String): Either[ObsError, Unit] =
        assert(text.nonEmpty)
        Left(ObsError.Transport(message = "Send failed"))
    broken.hello()
    assertEquals(
      ObsClient.withTransport(transport = broken)(_ => ()),
      Left(ObsError.Transport(message = "Send failed")),
    )
    assert(broken.closed.tryReceive().nonEmpty)
    for capabilities <-
        Vector(
          empty,
          JsonObject(fields =
            Map("availableRequests" -> JsonValue.Arr(value = Vector(JsonValue.Num(value = BigDecimal(1)))))
          ),
        )
    do
      supervised:
        val peer = new Peer
        forkDiscard:
          peer.hello()
          peer.identify().discard
          peer.respond(request = peer.sent.receive(), response = capabilities)
        assert(
          ObsClient
            .withTransport(transport = peer, config = config)(_ => ())
            .left
            .exists(_.isInstanceOf[ObsError.MalformedPayload])
        )

  test("authentication rejection preserves its WebSocket close code"):
    val peer = new Peer
    peer.inbound.send(Left(ObsError.Transport(message = "Authentication failed", closeCode = Some(4009))))
    assertEquals(
      ObsClient.withTransport(transport = peer, config = config)(_ => ()),
      Left(ObsError.Authentication(message = "Server rejected authentication", closeCode = Some(4009))),
    )

  test("close code 4009 after identification keeps its transport classification"):
    val failure = ObsError.Transport(message = closedWebSocketMessage, closeCode = Some(4009))
    val result  = connected() { peer =>
      peer.sent.receive().discard
      peer.inbound.send(Left(failure))
    } { (session, _) => assertEquals(session.rawRequest(requestType = "Echo"), Left(failure)) }
    assertEquals(result, Right(()))

  test("oversized outbound payloads fail only the offending request or batch"):
    val oversized = ObsError.MessageTooLarge(message = "Outgoing message exceeds configured byte limit")
    val peer      = new Peer:
      override def send(text: String): Either[ObsError, Unit] =
        if text.length > 512 then Left(oversized) else super.send(text = text)
    supervised:
      forkDiscard:
        peer.hello()
        peer.identify().discard
        peer.discover()
        peer.respond(request = peer.sent.receive())
      val big    = "x" * 4096
      val result = ObsClient.withTransport(transport = peer, config = config): session =>
        assertEquals(
          session
            .rawRequest(requestType = "Echo", data = JsonObject(fields = Map("blob" -> JsonValue.Str(value = big)))),
          Left(oversized),
        )
        assertEquals(session.batch(requests = Vector(RawRequest(requestType = s"E$big"))), Left(oversized))
        assertEquals(session.state, Right(ConnectionState.Ready))
        assertEquals(session.rawRequest(requestType = "Echo"), Right(empty))
      assertEquals(result, Right(()))
      assert(peer.closed.tryReceive().nonEmpty)

  test("a throwing transport receive fails the session instead of stalling"):
    val peer = new Peer:
      override def receive(): Either[ObsError, String] = throw new IllegalStateException("reader defect")
    assertEquals(
      ObsClient.withTransport(transport = peer, config = config)(_ => ()),
      Left(ObsError.InternalError(message = "Transport receive defect: java.lang.IllegalStateException")),
    )
    assert(peer.closed.tryReceive().nonEmpty)

  test("a throwing transport send fails the session instead of stalling"):
    val peer = new Peer:
      override def send(text: String): Either[ObsError, Unit] = throw new IllegalStateException("writer defect")
    peer.hello()
    assertEquals(
      ObsClient.withTransport(transport = peer, config = config)(_ => ()),
      Left(ObsError.InternalError(message = "Transport send defect: java.lang.IllegalStateException")),
    )
    assert(peer.closed.tryReceive().nonEmpty)

  test("typed request and batch decoder failures remain typed"):
    val request = new Request[String]:
      def requestType: String                                             = "Echo"
      def requestData: JsonObject                                         = empty
      def decodeResponse(data: JsonObject): Either[ProtocolError, String] = data.string(name = "required")
    val result = connected() { peer =>
      peer.respond(request = peer.sent.receive())
      val batch = peer.sent.receive()
      peer.emit(
        op   = 9,
        data = JsonObject(
          fields = Map(
            "requestId" -> batch.data.fields("requestId"),
            "results"   -> JsonValue.Arr(value = Vector(batchItem(name = "Echo"))),
          )
        ),
      )
      val malformedBatch = peer.sent.receive()
      peer.emit(op = 9, data = JsonObject(fields = Map("requestId" -> malformedBatch.data.fields("requestId"))))
    } { (session, _) =>
      assert(session.request(request = request).left.exists(_.isInstanceOf[ObsError.MalformedPayload]))
      assert(
        session
          .typedBatch(calls = Tuple1(BatchCall(request = request)))
          .exists(_.head match
            case TypedBatchResult.Completed(Left(_: ObsError.MalformedPayload)) => true
            case _                                                              => false)
      )
      assert(session.batch(requests = Vector(request)).left.exists(_.isInstanceOf[ObsError.MalformedPayload]))
    }
    assertEquals(result, Right(()))

  test("invalid typed request fields are rejected before sending"):
    val invalid = com.worxbend.obs.websocket.client.protocol.requests.SetCurrentProgramScene(sceneName = Field.Null)
    val result  = connected()(_ => ()) { (session, _) =>
      // Typed batch entries are capability-checked exactly like single typed requests.
      assertEquals(
        session.batch(requests = Vector(GetSceneList())),
        Left(ObsError.UnsupportedRequest(requestType = "GetSceneList")),
      )
      supervised:
        val allowed = new ObsSession(
          metadata     = session.metadata.copy(availableRequests = Set(invalid.requestType)),
          config       = config,
          dependencies = SessionDependencies.live,
          logic        = ox.channels.Actor.create(
            new SessionLogic(
              config                 = config,
              authenticationPassword = None,
              outgoing               = Channel.buffered[WireMessage](1),
              identified             = Channel.buffered[Either[ObsError, ConnectionMetadata]](1),
            )
          ),
        )
        assert(allowed.request(request = invalid).left.exists(_.isInstanceOf[ObsError.InvalidRequest]))
        assert(allowed.batch(requests = Vector(invalid)).left.exists(_.isInstanceOf[ObsError.InvalidRequest]))
    }
    assertEquals(result, Right(()))

  test("raw event subscriptions preserve new fields of known events"):
    val result = connected() { peer =>
      val request = peer.sent.receive()
      peer.emit(
        op   = 5,
        data = JsonObject(
          fields = Map(
            "eventType"   -> JsonValue.Str(value = "CurrentProgramSceneChanged"),
            "eventIntent" -> JsonValue.Num(value = BigDecimal(4)),
            "eventData"   -> JsonObject(
              fields = Map(
                "sceneName"   -> JsonValue.Str(value = "Scene"),
                "sceneUuid"   -> JsonValue.Str(value = "uuid"),
                "futureField" -> JsonValue.Str(value = "preserved"),
              )
            ),
          )
        ),
      )
      peer.respond(request = request)
    } { (session, _) =>
      session.withRawEvents() { events =>
        assertEquals(session.rawRequest(requestType = "Echo"), Right(empty))
        events.next() match
          case Next.Item(event) =>
            assert(event match
              case UnknownEvent("CurrentProgramSceneChanged", _) => true
              case _                                             => false)
            assertEquals(
              event.eventData.string(name = "futureField").left.map(SessionWire.malformed),
              Right("preserved"),
            )
          case other => fail(s"Expected an event, got $other")
      }
    }
    assertEquals(result, Right(Right(())))

  test("parallel batches fail locally before sending any application request"):
    val result = connected()(_ => ()) { (session, peer) =>
      for policy <- BatchFailurePolicy.values do
        assert(
          session
            .batch(
              requests      = Vector(RawRequest(requestType = "Echo")),
              execution     = BatchExecution.Parallel,
              failurePolicy = policy,
            )
            .left
            .exists(_.isInstanceOf[ObsError.InvalidConfiguration])
        )
      assert(
        session
          .typedBatch(
            calls     = Tuple1(BatchCall(request = RawRequest(requestType = "Echo"))),
            execution = BatchExecution.Parallel,
          )
          .isLeft
      )
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
    assert(config.copy(uri = "not a uri").toString.contains("<invalid>"))

  test("close code 4010 during identification maps to incompatible protocol"):
    val peer = new Peer
    peer.inbound.send(Left(ObsError.Transport(message = "Unsupported RPC version", closeCode = Some(4010))))
    assertEquals(
      ObsClient.withTransport(transport = peer, config = config)(_ => ()),
      Left(ObsError.IncompatibleProtocol(version = 1)),
    )

  test("close code 4011 during identification maps to the authentication family"):
    val peer = new Peer
    peer.inbound.send(Left(ObsError.Transport(message = "Session invalidated", closeCode = Some(4011))))
    assertEquals(
      ObsClient.withTransport(transport = peer, config = config)(_ => ()),
      Left(ObsError.Authentication(message = "Session invalidated by the server", closeCode = Some(4011))),
    )

  test("other close codes during the handshake keep their transport classification"):
    val peer = new Peer
    peer.inbound.send(Left(ObsError.Transport(message = closedWebSocketMessage, closeCode = Some(4000))))
    assertEquals(
      ObsClient.withTransport(transport = peer, config = config)(_ => ()),
      Left(ObsError.Transport(message = closedWebSocketMessage, closeCode = Some(4000))),
    )

  private val versionRead: Request[JsonObject] = new Request[JsonObject]:
    def requestType: String                                                 = "GetVersion"
    def requestData: JsonObject                                             = JsonObject.empty
    def decodeResponse(data: JsonObject): Either[ProtocolError, JsonObject] = Right(data)

  test("per-operation deadlines validate and override the session default"):
    connected() { peer =>
      val request = peer.sent.receive()
      assertEquals(request.data.string(name = "requestType"), Right("Echo"))
    } { (session, _) =>
      assert(session.rawRequest(requestType = "Echo", options = RequestOptions(timeout = Some(Duration.Zero))).isLeft)
      assertEquals(
        session.rawRequest(requestType = "Echo", options = RequestOptions(timeout = Some(10.millis))),
        Left(ObsError.Timeout(operation = "Echo")),
      )
    }

  test("response envelopes retain unknown fields and decode errors from exactly one request"):
    val payload = JsonObject(fields = Map("future" -> JsonValue.Str(value = "retained")))
    connected()(peer => peer.respond(request = peer.sent.receive(), response = payload)) { (session, _) =>
      val envelope =
        session.requestEnvelope(request = com.worxbend.obs.websocket.client.protocol.requests.GetVersion()).toOption.get
      assertEquals(envelope.raw, payload)
      assert(envelope.decoded.isLeft)
    }

  test("configured facade adapter shares request budget and typed decoder"):
    connected()(peer => peer.respond(request = peer.sent.receive())) { (session, _) =>
      assertEquals(
        session.withOptions(options = RequestOptions(timeout = Some(1.second))).request(request = versionRead),
        Right(empty),
      )
    }

  test("readiness retries explicit NotReady then returns read response"):
    connected() { peer =>
      peer.respond(request = peer.sent.receive(), code = 207)
      peer.respond(
        request  = peer.sent.receive(),
        response = JsonObject(fields = Map("ready" -> JsonValue.Bool(value = true))),
      )
    } { (session, _) =>
      assertEquals(
        session
          .requestWhenReady(request = versionRead, policy = ReadinessPolicy(maxAttempts = 2, delay = Duration.Zero))
          .toOption
          .get
          .boolean(name = "ready"),
        Right(true),
      )
    }

  test("readiness rejects extension requests, mutation requests and invalid policies locally"):
    connected()(_ => ()) { (session, _) =>
      assert(session.requestWhenReady(request = RawRequest(requestType = "GetVersion")).isLeft)
      assert(
        session.requestWhenReady(request = com.worxbend.obs.websocket.client.protocol.requests.StartRecord()).isLeft
      )
      assert(session.requestWhenReady(request = versionRead, policy = ReadinessPolicy(maxAttempts = 0)).isLeft)
      assert(
        session.requestWhenReady(request = versionRead, options = RequestOptions(timeout = Some(Duration.Zero))).isLeft
      )
    }

  test("readiness attempts stop at their configured limit"):
    connected()(peer => peer.respond(request = peer.sent.receive(), code = 207)) { (session, _) =>
      assert(
        session
          .requestWhenReady(request = versionRead, policy = ReadinessPolicy(maxAttempts = 1))
          .left
          .toOption
          .exists {
            case ObsError.RequestRejected(_, _, 207, _) => true
            case _                                      => false
          }
      )
    }

  test("readiness total deadline includes backoff"):
    connected()(peer => peer.respond(request = peer.sent.receive(), code = 207)) { (session, _) =>
      assertEquals(
        session.requestWhenReady(
          request = versionRead,
          policy  = ReadinessPolicy(maxAttempts = 2, delay = 1.second),
          options = RequestOptions(timeout = Some(20.millis)),
        ),
        Left(ObsError.Timeout(operation = "GetVersion")),
      )
    }

  test("startup readiness recovery is opt-in and finishes discovery before invoking the callback"):
    supervised:
      val peer = new Peer
      forkDiscard:
        peer.hello()
        peer.identify().discard
        peer.respond(request = peer.sent.receive(), code = 207)
        peer.discover()
      assertEquals(
        ObsClient.withTransport(
          transport = peer,
          config    = config.copy(readiness = Some(ReadinessPolicy(maxAttempts = 2, delay = Duration.Zero))),
        )(
          use = _.metadata.availableRequests.contains("GetVersion")
        ),
        Right(true),
      )
      assert(config.copy(readiness = Some(ReadinessPolicy(maxAttempts = 0))).validate.isLeft)

  test("diagnostics record metadata and complete counters despite bounded observer overflow"):
    connected() { peer =>
      peer.respond(request = peer.sent.receive())
    } { (session, _) =>
      assert(session.withDiagnostics(capacity = 0)(_ => ()).isLeft)
      val observed = session.withDiagnostics(capacity = 1): diagnostics =>
        // The writer fork's traffic accounting trails the wire send and races the statistics read
        // below. This observer receives each Echo record exactly as it lands — send traffic, receive
        // traffic, request completion — so draining all three makes every counter deterministic.
        val drained = session.withDiagnostics(capacity = 16): echo =>
          assertEquals(session.rawRequest(requestType = "Echo"), Right(empty))
          assert(echo.next().isRight)
          assert(echo.next().isRight)
          assert(echo.next().isRight)
        assert(drained.isRight)
        val stats = session.statistics.toOption.get
        assertEquals(stats.completedRequests, 2L)
        assertEquals(stats.failedRequests, 0L)
        assert(stats.sentMessages >= 3L)
        assert(stats.receivedMessages >= 4L)
        assert(stats.sentBytes > 0L && stats.receivedBytes > 0L)
        assert(diagnostics.next().isRight)
        assert(diagnostics.droppedDiagnostics > 0L)
      assert(observed.isRight)
      session.close()
      assert(session.withDiagnostics()(_ => ()).isLeft)
    }

  test("typed event subscriptions select matching generated values"):
    connected()(_ => ()) { (session, peer) =>
      val selector = com.worxbend.obs.websocket.client.protocol.events.CurrentProgramSceneChanged.selector
      assertEquals(
        session.withEvents(selector = selector): subscription =>
          peer.emit(
            op   = 5,
            data = JsonObject(
              fields = Map(
                "eventType"   -> JsonValue.Str(value = "CurrentProgramSceneChanged"),
                "eventIntent" -> JsonValue.Num(value = BigDecimal(1)),
                "eventData"   -> JsonObject(fields =
                  Map("sceneName" -> JsonValue.Str(value = "Main"), "sceneUuid" -> JsonValue.Str(value = "id"))
                ),
              )
            ),
          )
          val event = subscription.next() match
            case Next.Item(event) => event
            case other            => fail(s"Expected an event, got $other")
          assertEquals(event.sceneName, "Main")
          assertEquals(subscription.droppedEvents, 0L)
        ,
        Right(()),
      )
    }

  test("readiness returns non-NotReady server rejections without another attempt"):
    connected()(peer => peer.respond(request = peer.sent.receive(), code = 500)) { (session, _) =>
      assert(session.requestWhenReady(request = versionRead).left.toOption.exists {
        case ObsError.RequestRejected(_, _, 500, _) => true
        case _                                      => false
      })
    }

  test("startup readiness is bounded by the total discovery deadline"):
    supervised:
      val peer = new Peer
      forkDiscard:
        peer.hello()
        peer.identify().discard
        peer.respond(request = peer.sent.receive(), code = 207)
      val result = ObsClient.withTransport(
        transport = peer,
        config    =
          config.copy(requestTimeout = 20.millis, readiness = Some(ReadinessPolicy(maxAttempts = 2, delay = 1.second))),
      )(_ => ())
      assertEquals(result, Left(ObsError.Timeout(operation = "GetVersion")))

  private def closeFailure(error: Throwable): Peer = new Peer:
    override def close(): Unit =
      super.close()
      throw error

  test("a nonfatal close defect does not replace the typed configuration failure"):
    val peer    = closeFailure(error = new IllegalStateException(cleanupDefectMessage))
    val invalid = config.copy(uri = httpEndpoint)
    assertEquals(ObsClient.withTransport(transport = peer, config = invalid)(_ => ()), invalid.validate.map(_ => ()))
    assert(peer.closed.tryReceive().nonEmpty)

  test("a nonfatal close defect preserves a successful callback result"):
    supervised:
      val peer = closeFailure(error = new IllegalStateException(cleanupDefectMessage))
      forkDiscard:
        peer.hello()
        peer.identify().discard
        peer.discover()
      assertEquals(ObsClient.withTransport(transport = peer, config = config)(_ => 42), Right(42))
      assert(peer.closed.tryReceive().nonEmpty)

  test("a nonfatal close defect preserves callback cancellation"):
    val cancelled = new InterruptedException("cancelled")
    val observed  = captureInterruption:
      supervised:
        val peer = closeFailure(error = new IllegalStateException(cleanupDefectMessage))
        forkDiscard:
          peer.hello()
          peer.identify().discard
          peer.discover()
        ObsClient.withTransport(transport = peer, config = config)(_ => throw cancelled).discard
    assert(observed eq cancelled)

  test("interruption from transport close propagates"):
    val interrupted = new InterruptedException("close interrupted")
    val peer        = closeFailure(error = interrupted)
    val observed    = captureInterruption:
      ObsClient.withTransport(transport = peer, config = config.copy(uri = httpEndpoint))(_ => ()).discard
    assert(observed eq interrupted)

  test("fatal transport close errors propagate"):
    val fatal    = new java.lang.InternalError("synthetic fatal cleanup error")
    val peer     = closeFailure(error = fatal)
    val observed = try
      ObsClient.withTransport(transport = peer, config = config.copy(uri = httpEndpoint))(_ => ()).discard
      fail("Expected the fatal cleanup error to propagate")
    catch case error: java.lang.InternalError => error
    assert(observed eq fatal)

  private def captureInterruption(operation: => Unit): InterruptedException =
    try
      operation
      fail("Expected cancellation to propagate")
    catch case error: InterruptedException => error
    finally Thread.interrupted().discard

  test("oversized incoming JSON retains its size-limit classification"):
    val peer = new Peer
    peer.hello()
    val result = ObsClient.withTransport(transport = peer, config = config.copy(maxMessageBytes = 1))(_ => ())
    assertEquals(result, Left(ObsError.MessageTooLarge(message = "Incoming message exceeds configured byte limit")))

  test("subscription drop counts remain readable after callback and session scopes end"):
    val escaped = connected(cfg = config.copy(subscriptionCapacity = 1)) { peer =>
      val request     = peer.sent.receive()
      peer.event(index     = 1)
      peer.event(index     = 2)
      peer.respond(request = request)
    } { (session, _) =>
      session.withEvents(policy = OverflowPolicy.DropOldest): subscription =>
        assertEquals(session.rawRequest(requestType = "Echo"), Right(empty))
        subscription
    }.toOption.get.toOption.get
    assertEquals(escaped.droppedEvents, 1L)

  test("diagnostics subscribers observe the concrete session failure once before completion"):
    val failure = ObsError.Transport(message = "Peer closed", closeCode = Some(1006))
    val result  = connected()(_ => ()) { (session, peer) =>
      session.withDiagnostics(): diagnostics =>
        peer.inbound.send(Left(failure))
        val observed = diagnostics.flow.runToList()
        assertEquals(observed.count(_ == Left(failure)), 1)
        assertEquals(observed.last, Left(failure))
    }
    assertEquals(result, Right(Right(())))

  test("diagnostics flows complete silently when the session closes cleanly"):
    val result = connected()(_ => ()) { (session, _) =>
      session.withDiagnostics(): diagnostics =>
        session.close()
        assert(diagnostics.flow.runToList().forall(_.isRight))
    }
    assertEquals(result, Right(Right(())))

  test("requests rejected at registration do not move session statistics"):
    val observed = Channel.buffered[Unit](1)
    val release  = Channel.buffered[Unit](1)
    val result   = connected(cfg = config.copy(maxInFlight = 1)) { peer =>
      val first = peer.sent.receive()
      observed.send(())
      release.receive()
      peer.respond(request = first)
    } { (session, _) =>
      supervised:
        val drained = session.withDiagnostics(capacity = 16): settled =>
          val pending = fork(session.rawRequest(requestType = "First"))
          observed.receive()
          val before = session.statistics.toOption.get
          assertEquals(
            session.rawRequest(requestType = "Second"),
            Left(ObsError.Overflow(resource = inFlightLimitResource)),
          )
          val after = session.statistics.toOption.get
          // The peer observing the frame does not imply the writer fork's traffic(Sent) ask has landed:
          // that accounting races these snapshots, so only the request-finish counters are compared
          // here — exactly the counters a registration rejection must not move.
          assertEquals(after.completedRequests, before.completedRequests)
          assertEquals(after.failedRequests, before.failedRequests)
          assertEquals(after.requestElapsedNanos, before.requestElapsedNanos)
          release.send(())
          assertEquals(pending.join(), Right(empty))
          session.close()
          // Exactly four records reach this observer: First's send and receive traffic, its
          // RequestFinished, and the Closed state change. Draining them bounds the writer fork's
          // trailing accounting, so every counter is settled below this line. Each read carries a
          // short deadline so a lost record fails fast instead of hanging the suite.
          def drainedRecord(): Unit =
            assert(ox.timeout(2.seconds)(settled.next()).isRight)
          drainedRecord()
          drainedRecord()
          drainedRecord()
          drainedRecord()
          val closed = session.statistics.toOption.get
          assertEquals(closed.completedRequests, before.completedRequests + 1)
          assertEquals(session.rawRequest(requestType = "Echo"), Left(ObsError.Closed))
          assertEquals(session.statistics.toOption.get, closed)
        assert(drained.isRight)
    }
    assertEquals(result, Right(()))

  test("a defective bookkeeping tell does not kill the session"):
    supervised:
      val logic = Actor.create(
        new SessionLogic(
          config                 = config,
          authenticationPassword = None,
          outgoing               = Channel.buffered[WireMessage](1),
          identified             = Channel.buffered[Either[ObsError, ConnectionMetadata]](1),
        )
      )
      val session =
        new ObsSession(
          metadata =
            ConnectionMetadata(obsWebSocketVersion = "5.6.3", negotiatedRpcVersion = 1, availableRequests = Set.empty),
          config       = config,
          dependencies = SessionDependencies.live,
          logic        = logic,
        )
      session.tellSafely(_ => throw new IllegalStateException("bookkeeping defect"))
      assertEquals(session.state, Right(ConnectionState.AwaitingHello))

  test("an interrupted registration cannot orphan a pending request entry"):
    supervised:
      val outgoing   = Channel.buffered[WireMessage](4)
      val identified = Channel.buffered[Either[ObsError, ConnectionMetadata]](1)
      val logic      = Actor.create(
        new SessionLogic(config = config, authenticationPassword = None, outgoing = outgoing, identified = identified)
      )
      val session = new ObsSession(
        metadata =
          ConnectionMetadata(obsWebSocketVersion = "5.6.3", negotiatedRpcVersion = 1, availableRequests = Set.empty),
        config       = config,
        dependencies = SessionDependencies(nextRequestId = () => interruptedRegistrationId),
        logic        = logic,
      )
      // Drive the handshake so a registration can succeed.
      logic
        .ask(
          _.incoming(
            message = WireMessage(
              op   = 0,
              data = JsonObject(
                fields = Map(
                  "rpcVersion"          -> JsonValue.Num(value = BigDecimal(1)),
                  "obsWebSocketVersion" -> JsonValue.Str(value = "5.6.3"),
                )
              ),
            )
          )
        )
        .discard
      outgoing.receive().discard // Identify
      logic
        .ask(
          _.incoming(message =
            WireMessage(
              op   = 2,
              data = JsonObject(fields = Map("negotiatedRpcVersion" -> JsonValue.Num(value = BigDecimal(1)))),
            )
          )
        )
        .discard
      identified.receive().discard
      // Stall the actor so the register invocation queues behind the blocked one.
      val entered = Channel.buffered[Unit](1)
      val release = Channel.buffered[Unit](1)
      val stalled = fork:
        logic.ask: _ =>
          entered.send(())
          release.receive()
      entered.receive()
      // The register ask is enqueued but still unprocessed when the timeout interrupts the caller.
      assertEquals(timeoutOption(500.millis)(session.rawRequest(requestType = "Probe")), None)
      release.send(())
      stalled.join()
      // The queued register ran and the unconditional cancel removed the pending entry again: the
      // request id is unknown to the writer guard, nothing was accounted, yet the message did queue —
      // proving the enqueue-then-interrupt path (not a registration that never landed) was exercised.
      val orphaned = WireMessage(
        op   = 6,
        data = JsonObject(fields = Map("requestId" -> JsonValue.Str(value = interruptedRegistrationId))),
      )
      assertEquals(logic.ask(_.canSend(message = orphaned)), false)
      assertEquals(logic.ask(_.statistics), SessionStats())
      assert(outgoing.tryReceive().exists(_.data.string(name = "requestId").contains(interruptedRegistrationId)))
