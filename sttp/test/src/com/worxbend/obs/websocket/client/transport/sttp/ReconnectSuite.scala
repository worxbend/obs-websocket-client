package com.worxbend.obs.websocket.client.transport.sttp

import com.worxbend.obs.websocket.client.*
import com.worxbend.obs.websocket.client.protocol.*
import munit.FunSuite
import ox.channels.{Channel, ChannelClosed}
import scala.concurrent.duration.*

class ReconnectSuite extends FunSuite:
  private val transient = ObsError.Transport("temporary")
  private val policy = ReconnectPolicy.create(3, 1.millis, 4.millis, 0.0).toOption.get
  private val immediate = ReconnectTiming(() => 0.5, _ => ())

  private class Peer extends ObsTransport:
    private val inbound = Channel.buffered[Either[ObsError, String]](16)
    var messages = Vector.empty[WireMessage]
    var closed = false
    emit(
      0,
      JsonObject(Map("obsWebSocketVersion" -> JsonValue.Str("5.7.0"), "rpcVersion" -> JsonValue.Num(BigDecimal(1))))
    )
    private def emit(op: Int, data: JsonObject): Unit = inbound.send(Right(Protocol.encode(WireMessage(op, data))))
    def send(text: String): Either[ObsError, Unit] =
      val message = Protocol.decode(text).toOption.get
      messages = messages :+ message
      message.op match
        case 1 =>
          emit(2, JsonObject(Map("negotiatedRpcVersion" -> JsonValue.Num(BigDecimal(1)))))
          Right(())
        case 6 if message.data.string("requestType").contains("GetVersion") =>
          emit(
            7,
            JsonObject(
              Map(
                "requestType" -> JsonValue.Str("GetVersion"),
                "requestId" -> message.data.fields("requestId"),
                "requestStatus" -> JsonObject(
                  Map("result" -> JsonValue.Bool(true), "code" -> JsonValue.Num(BigDecimal(100)))
                ),
                "responseData" -> JsonObject(Map("availableRequests" -> JsonValue.Arr(Vector.empty)))
              )
            )
          )
          Right(())
        case _ => Left(transient)
    def receive(): Either[ObsError, String] = inbound.receiveOrClosed() match
      case Right(text)      => Right(text)
      case Left(error)      => Left(error)
      case _: ChannelClosed => Left(ObsError.Closed)
    def close(): Unit =
      closed = true
      val _ = inbound.doneOrClosed()

  private class Connector(failures: List[ObsError] = Nil) extends ReconnectConnector:
    var calls = 0
    var peers = Vector.empty[Peer]
    var configs = Vector.empty[ObsConfig]
    def connect[A](config: ObsConfig)(use: ObsSession => A): Either[ObsError, A] =
      calls += 1
      configs = configs :+ config
      failures.lift(calls - 1) match
        case Some(error) => Left(error)
        case None        =>
          val peer = new Peer
          peers = peers :+ peer
          ObsClient.withTransport(peer, config)(use)

  test("reconnect policy validates every bound and caps exponential jitter"):
    assert(ReconnectPolicy.create(-1).isLeft)
    assert(ReconnectPolicy.create(initialDelay = Duration.Zero).isLeft)
    assert(ReconnectPolicy.create(initialDelay = 2.seconds, maxDelay = 1.second).isLeft)
    List(Double.NaN, Double.PositiveInfinity, -0.1, 1.1).foreach: fraction =>
      assert(ReconnectPolicy.create(jitterFraction = fraction).isLeft)
    val defaults = ReconnectPolicy.create().toOption.get
    assertEquals(defaults.maxRetries, 5)
    assertEquals(defaults.initialDelay, 250.millis)
    assertEquals(defaults.maxDelay, 10.seconds)
    assertEquals(defaults.jitterFraction, 0.2)
    assertEquals(policy.delay(0, 0.5), Right(1.millis))
    assertEquals(policy.delay(1, 0.5), Right(2.millis))
    assertEquals(policy.delay(Int.MaxValue, 1.0), Right(4.millis))
    val fullJitter = ReconnectPolicy.create(3, 1.millis, 4.millis, 1.0).toOption.get
    assertEquals(fullJitter.delay(0, 0.0), Right(Duration.Zero))
    assertEquals(fullJitter.delay(0, 1.0), Right(2.millis))
    List(Double.NaN, -0.1, 1.1).foreach(sample => assert(policy.delay(0, sample).isLeft))

  test("transient opening failures retry with bounded delays before first connected notice"):
    val connector = new Connector(List(transient, ObsError.Timeout("connection")))
    var delays = Vector.empty[FiniteDuration]
    var notices = Vector.empty[ReconnectNotice]
    val timing = ReconnectTiming(() => 0.5, delay => delays = delays :+ delay)
    val result = ReconnectingObsClient.withConnector(
      connector,
      ObsConfig(),
      policy,
      timing,
      notice => notices = notices :+ notice
    ): (generation, _) =>
      ReconnectDecision.Complete(generation.value)
    assertEquals(result, Right(3L))
    assertEquals(delays, Vector(1.millis, 2.millis))
    assertEquals(
      notices.filter(_.isInstanceOf[ReconnectNotice.Connected]),
      Vector(ReconnectNotice.Connected(ConnectionGeneration(3)))
    )
    assertEquals(connector.calls, 3)

  test("fresh scopes restore desired mask refresh password and never replay a mutation"):
    val connector = new Connector
    var passwordReads = 0
    val credentials = new PasswordProvider:
      def password(): Either[ObsError, Option[String]] =
        passwordReads += 1
        Right(Some(s"password-$passwordReads"))
    var notices = Vector.empty[ReconnectNotice]
    var previous = Option.empty[ObsSession]
    val desired = EventSubscriptions.fromLong(65536).toOption.get
    val result = ReconnectingObsClient.withConnector(
      connector,
      ObsConfig(passwordProvider = credentials),
      policy,
      immediate,
      notice => notices = notices :+ notice
    ): (generation, session) =>
      if generation.value == 1 then
        previous = Some(session)
        val error = session.rawRequest("Mutate").swap.toOption.get
        ReconnectDecision.Retry(error, desired)
      else
        assertEquals(previous.get.state, Left(ObsError.Closed))
        ReconnectDecision.Complete(generation.value)
    assertEquals(result, Right(2L))
    assertEquals(passwordReads, 2)
    assert(connector.peers.forall(_.closed))
    assertEquals(connector.peers.flatMap(_.messages).count(_.data.string("requestType").contains("Mutate")), 1)
    assertEquals(connector.configs.last.eventSubscriptions, desired)
    assertEquals(connector.peers.last.messages.head.data.int("eventSubscriptions"), Right(65536))
    assert(notices.contains(ReconnectNotice.EventGap(ConnectionGeneration(1), transient)))
    assert(notices.contains(ReconnectNotice.Reconnected(ConnectionGeneration(2), ConnectionGeneration(1))))

  test("fatal authentication protocol and OBS close failures stop without retry"):
    val fatal = List(
      ObsError.Authentication("rejected"),
      ObsError.IncompatibleProtocol(0),
      ObsError.MalformedPayload("rpcVersion", "invalid"),
      ObsError.InvalidRequest("inputVolumeDb", "out of range"),
      ObsError.Transport("invalidated", Some(4011)),
      ObsError.Transport("authentication", Some(4009)),
      ObsError.Transport("unsupported RPC", Some(4010)),
      ObsError.Transport("normal", Some(1000)),
      ObsError.MessageTooLarge("Outgoing message exceeds configured byte limit"),
      ObsError.UnsupportedMessage("Binary messages are unsupported; use OBS JSON encoding"),
      ObsError.InternalError("Invalid state transition: Ready -> Identifying"),
      ObsError.Closed
    )
    fatal.foreach: error =>
      val connector = new Connector(List(error))
      val result = ReconnectingObsClient.withConnector(connector, ObsConfig(), policy, immediate, _ => ()): (_, _) =>
        fail("fatal handshake must not invoke application")
      assertEquals(result, Left(error))
      assertEquals(connector.calls, 1)
    List(1001, 1006, 1011, 1012, 1013).foreach(code =>
      assert(ReconnectPolicy.retryable(ObsError.Transport("transient", Some(code))))
    )

  test("retry budget includes every connection attempt and terminates"):
    val connector = new Connector(List.fill(8)(transient))
    val result = ReconnectingObsClient.withConnector(connector, ObsConfig(), policy, immediate, _ => ()): (_, _) =>
      ReconnectDecision.Complete(())
    assertEquals(result, Left(transient))
    assertEquals(connector.calls, 4)

  test("event gap notice fires only when the retry budget still covers another attempt"):
    val connector = new Connector(List.fill(3)(transient))
    var notices = Vector.empty[ReconnectNotice]
    val result = ReconnectingObsClient.withConnector(
      connector,
      ObsConfig(),
      policy,
      immediate,
      notice => notices = notices :+ notice
    ): (_, _) =>
      ReconnectDecision.Retry(transient, ObsConfig().eventSubscriptions)
    assertEquals(result, Left(transient))
    assertEquals(connector.calls, 4)
    assert(notices.exists(_.isInstanceOf[ReconnectNotice.RetryScheduled]))
    assert(!notices.exists(_.isInstanceOf[ReconnectNotice.EventGap]))

  test("a non-retryable retry decision reports no event gap"):
    val connector = new Connector
    var notices = Vector.empty[ReconnectNotice]
    val fatal = ObsError.Authentication("rejected")
    val result = ReconnectingObsClient.withConnector(
      connector,
      ObsConfig(),
      policy,
      immediate,
      notice => notices = notices :+ notice
    ): (_, _) =>
      ReconnectDecision.Retry(fatal, ObsConfig().eventSubscriptions)
    assertEquals(result, Left(fatal))
    assertEquals(connector.calls, 1)
    assert(!notices.exists(_.isInstanceOf[ReconnectNotice.EventGap]))

  test("notice callback defects surface as internal errors within the either contract"):
    val connector = new Connector
    val result = ReconnectingObsClient.withConnector(
      connector,
      ObsConfig(),
      policy,
      immediate,
      _ => throw new IllegalArgumentException("listener defect")
    ): (_, _) =>
      ReconnectDecision.Complete(())
    assert(result.left.exists(_.isInstanceOf[ObsError.InternalError]))
    assertEquals(connector.calls, 1)
    assert(connector.peers.head.closed)

  test("notice callbacks propagate interruption for cancellation"):
    val connector = new Connector(List(transient))
    val interrupted =
      try
        val _ = ReconnectingObsClient.withConnector(
          connector,
          ObsConfig(),
          policy,
          immediate,
          _ => throw new InterruptedException("cancelled")
        ): (_, _) =>
          ReconnectDecision.Complete(())
        false
      catch case _: InterruptedException => true
      finally
        val _ = Thread.interrupted()
    assert(interrupted)
    assertEquals(connector.calls, 1)

  test("application stop prevents retry even for a transient cause"):
    val connector = new Connector
    val result = ReconnectingObsClient.withConnector(connector, ObsConfig(), policy, immediate, _ => ()): (_, _) =>
      ReconnectDecision.Stop(transient)
    assertEquals(result, Left(transient))
    assertEquals(connector.calls, 1)

  test("invalid injected jitter stops before sleep and another connection"):
    val connector = new Connector(List(transient))
    val timing = ReconnectTiming(() => Double.NaN, _ => fail("must not sleep"))
    val result = ReconnectingObsClient.withConnector(connector, ObsConfig(), policy, timing, _ => ()): (_, _) =>
      ReconnectDecision.Complete(())
    assert(result.left.exists(_.isInstanceOf[ObsError.InvalidConfiguration]))
    assertEquals(connector.calls, 1)

  test("cancellation interrupts backoff without opening another scope"):
    val connector = new Connector(List(transient))
    val timing = ReconnectTiming(() => 0.5, _ => ox.never)
    val result = ox.timeoutOption(100.millis):
      ReconnectingObsClient.withConnector(connector, ObsConfig(), policy, timing, _ => ()): (_, _) =>
        ReconnectDecision.Complete(())
    assertEquals(result, None)
    assertEquals(connector.calls, 1)

  test("application failure propagates after closing the current scope without retry"):
    val connector = new Connector
    val _ = intercept[IllegalStateException]:
      ReconnectingObsClient.withConnector(connector, ObsConfig(), policy, immediate, _ => ()): (_, _) =>
        throw new IllegalStateException("application defect")
    assertEquals(connector.calls, 1)
    assert(connector.peers.head.closed)

  test("invalid initial configuration never starts a connector"):
    val connector = new Connector
    assert(
      ReconnectingObsClient
        .withConnector(connector, ObsConfig(maxInFlight = 0), policy, immediate, _ => ()): (_, _) =>
          ReconnectDecision.Complete(())
        .isLeft
    )
    assertEquals(connector.calls, 0)

  test("live timing supplies valid randomness and interruptible sleep"):
    val live = ReconnectTiming.live
    val sample = live.nextJitter()
    assert(sample >= 0.0 && sample < 1.0)
    live.sleep(Duration.Zero)

  test("opt-in public entrypoint owns a real sttp connection with defaults"):
    def serve(socket: java.net.Socket): Unit =
      LocalWebSocketPeer.upgrade(socket)
      LocalWebSocketPeer.send(socket, """{"op":0,"d":{"obsWebSocketVersion":"5.7.0","rpcVersion":1}}""")
      assertEquals(Protocol.decode(LocalWebSocketPeer.receive(socket)._2).toOption.get.op, 1)
      LocalWebSocketPeer.send(socket, """{"op":2,"d":{"negotiatedRpcVersion":1}}""")
      val request = Protocol.decode(LocalWebSocketPeer.receive(socket)._2).toOption.get
      val id = request.data.string("requestId").toOption.get
      LocalWebSocketPeer.send(
        socket,
        s"""{"op":7,"d":{"requestType":"GetVersion","requestId":"$id","requestStatus":{"result":true,"code":100},"responseData":{"availableRequests":[]}}}"""
      )
      assertEquals(LocalWebSocketPeer.receive(socket)._1, 8)
    val result = LocalWebSocketPeer.run(serve): uri =>
      ReconnectingObsClient.run(ObsConfig(uri = uri), policy): (generation, _) =>
        ReconnectDecision.Complete(generation.value)
    assertEquals(result, Right(1L))

  test("documented event-recovery example compiles and validates config before connecting"):
    def nextSceneChange(config: ObsConfig): Either[ObsError, Event] =
      ReconnectPolicy
        .create()
        .flatMap: policy =>
          ReconnectingObsClient.run(config, policy): (_, session) =>
            session.withEvents(Set("CurrentProgramSceneChanged"))(_.next()).flatten match
              case Right(event) => ReconnectDecision.Complete(event)
              case Left(error)  => ReconnectDecision.Retry(error, config.eventSubscriptions)
    assert(nextSceneChange(ObsConfig(uri = "http://invalid")).isLeft)
