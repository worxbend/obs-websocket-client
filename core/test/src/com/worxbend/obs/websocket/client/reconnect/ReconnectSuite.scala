package com.worxbend.obs.websocket.client.reconnect

import com.worxbend.obs.websocket.client.*
import com.worxbend.obs.websocket.client.protocol.*
import munit.FunSuite
import ox.channels.{Channel, ChannelClosed}
import scala.concurrent.duration.*

class ReconnectSuite extends FunSuite:
  private val transient = ObsError.Transport(message = "temporary")
  private val policy    = ReconnectPolicy
    .create(maxRetries = 3, initialDelay = 1.millis, maxDelay = 4.millis, jitterFraction = 0.0)
    .toOption
    .get
  private val immediate = ReconnectTiming(() => 0.5, _ => ())

  private class Peer extends ObsTransport:
    private val inbound = Channel.buffered[Either[ObsError, String]](16)
    var messages        = Vector.empty[WireMessage]
    var closed          = false
    emit(
      op   = 0,
      data = JsonObject(fields =
        Map(
          "obsWebSocketVersion" -> JsonValue.Str(value = "5.7.0"),
          "rpcVersion"          -> JsonValue.Num(value = BigDecimal(1)),
        )
      ),
    )
    private def emit(op: Int, data: JsonObject): Unit =
      inbound.send(Right(Protocol.encode(message = WireMessage(op = op, data = data))))
    def send(text: String): Either[ObsError, Unit] =
      val message = Protocol.decode(text = text).toOption.get
      messages = messages :+ message
      message.op match
        case 1 =>
          emit(op = 2, data = JsonObject(fields = Map("negotiatedRpcVersion" -> JsonValue.Num(value = BigDecimal(1)))))
          Right(())
        case 6 if message.data.string(name = "requestType").contains("GetVersion") =>
          emit(
            op   = 7,
            data = JsonObject(
              fields = Map(
                "requestType"   -> JsonValue.Str(value = "GetVersion"),
                "requestId"     -> message.data.fields("requestId"),
                "requestStatus" -> JsonObject(
                  fields =
                    Map("result" -> JsonValue.Bool(value = true), "code" -> JsonValue.Num(value = BigDecimal(100)))
                ),
                "responseData" -> JsonObject(fields = Map("availableRequests" -> JsonValue.Arr(value = Vector.empty))),
              )
            ),
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

  private class Connector(failures: List[ObsError] = Nil, script: List[Option[ObsError]] = Nil)
      extends ReconnectConnector:
    var calls                                                                    = 0
    var peers                                                                    = Vector.empty[Peer]
    var configs                                                                  = Vector.empty[ObsConfig]
    def connect[A](config: ObsConfig)(use: ObsSession => A): Either[ObsError, A] =
      calls += 1
      configs = configs :+ config
      val outcome = if script.nonEmpty then script.lift(calls - 1).flatten else failures.lift(calls - 1)
      outcome match
        case Some(error) => Left(error)
        case None        =>
          val peer = new Peer
          peers = peers :+ peer
          ObsClient.withTransport(transport = peer, config = config)(use = use)

  test("reconnect policy validates every bound and caps exponential jitter"):
    assert(ReconnectPolicy.create(maxRetries = -1).isLeft)
    assert(ReconnectPolicy.create(initialDelay = Duration.Zero).isLeft)
    assert(ReconnectPolicy.create(initialDelay = 2.seconds, maxDelay = 1.second).isLeft)
    List(Double.NaN, Double.PositiveInfinity, -0.1, 1.1).foreach: fraction =>
      assert(ReconnectPolicy.create(jitterFraction = fraction).isLeft)
    val defaults = ReconnectPolicy.create().toOption.get
    assertEquals(defaults.maxRetries, 5)
    assertEquals(defaults.initialDelay, 250.millis)
    assertEquals(defaults.maxDelay, 10.seconds)
    assertEquals(defaults.jitterFraction, 0.2)
    assertEquals(policy.delay(retry = 0, sample = 0.5), Right(1.millis))
    assertEquals(policy.delay(retry = 1, sample = 0.5), Right(2.millis))
    assertEquals(policy.delay(retry = Int.MaxValue, sample = 1.0), Right(4.millis))
    val fullJitter = ReconnectPolicy
      .create(maxRetries = 3, initialDelay = 1.millis, maxDelay = 4.millis, jitterFraction = 1.0)
      .toOption
      .get
    assertEquals(fullJitter.delay(retry = 0, sample = 0.0), Right(Duration.Zero))
    assertEquals(fullJitter.delay(retry = 0, sample = 1.0), Right(2.millis))
    List(Double.NaN, -0.1, 1.1).foreach(sample => assert(policy.delay(retry = 0, sample = sample).isLeft))

  test("transient opening failures retry with bounded delays before first connected notice"):
    val connector = new Connector(failures = List(transient, ObsError.Timeout(operation = "connection")))
    var delays    = Vector.empty[FiniteDuration]
    var notices   = Vector.empty[ReconnectNotice]
    val timing    = ReconnectTiming(() => 0.5, delay => delays = delays :+ delay)
    val result    = Reconnect.run(
      connector = connector,
      config    = ObsConfig(),
      policy    = policy,
      timing    = timing,
      notice => notices = notices :+ notice,
    ): (generation, _) =>
      ReconnectDecision.Complete(value = generation.value)
    assertEquals(result, Right(3L))
    assertEquals(delays, Vector(1.millis, 2.millis))
    assertEquals(
      notices.filter(_.isInstanceOf[ReconnectNotice.Connected]),
      Vector(ReconnectNotice.Connected(generation = ConnectionGeneration(value = 3))),
    )
    assertEquals(connector.calls, 3)

  test("fresh scopes restore desired mask refresh password and never replay a mutation"):
    val connector     = new Connector
    var passwordReads = 0
    val credentials   = new PasswordProvider:
      def password(): Either[ObsError, Option[String]] =
        passwordReads += 1
        Right(Some(s"password-$passwordReads"))
    var notices  = Vector.empty[ReconnectNotice]
    var previous = Option.empty[ObsSession]
    val desired  = EventSubscriptions.fromLong(value = 65536).toOption.get
    val result   = Reconnect.run(
      connector = connector,
      config    = ObsConfig(passwordProvider = credentials),
      policy    = policy,
      timing    = immediate,
      notice => notices = notices :+ notice,
    ): (generation, session) =>
      if generation.value == 1 then
        previous = Some(session)
        val error = session.rawRequest(requestType = "Mutate").swap.toOption.get
        ReconnectDecision.Retry(cause = error, desiredSubscriptions = desired)
      else
        assertEquals(previous.get.state, Left(ObsError.Closed))
        ReconnectDecision.Complete(value = generation.value)
    assertEquals(result, Right(2L))
    assertEquals(passwordReads, 2)
    assert(connector.peers.forall(_.closed))
    assertEquals(connector.peers.flatMap(_.messages).count(_.data.string(name = "requestType").contains("Mutate")), 1)
    assertEquals(connector.configs.last.eventSubscriptions, desired)
    assertEquals(connector.peers.last.messages.head.data.int(name = "eventSubscriptions"), Right(65536))
    assert(notices.contains(ReconnectNotice.EventGap(generation = ConnectionGeneration(value = 1), cause = transient)))
    assert(
      notices.contains(
        ReconnectNotice.Reconnected(
          generation = ConnectionGeneration(value = 2),
          previous   = ConnectionGeneration(value = 1),
        )
      )
    )

  test("fatal authentication protocol and OBS close failures stop without retry"):
    val fatal = List(
      ObsError.Authentication(message       = "rejected"),
      ObsError.IncompatibleProtocol(version = 0),
      ObsError.MalformedPayload(path        = "rpcVersion", message        = "invalid"),
      ObsError.InvalidRequest(path          = "inputVolumeDb", message     = "out of range"),
      ObsError.Transport(message            = "invalidated", closeCode     = Some(4011)),
      ObsError.Transport(message            = "authentication", closeCode  = Some(4009)),
      ObsError.Transport(message            = "unsupported RPC", closeCode = Some(4010)),
      ObsError.Transport(message            = "normal", closeCode          = Some(1000)),
      ObsError.MessageTooLarge(message      = "Outgoing message exceeds configured byte limit"),
      ObsError.UnsupportedMessage(message   = "Binary messages are unsupported; use OBS JSON encoding"),
      ObsError.InternalError(message        = "Invalid state transition: Ready -> Identifying"),
      ObsError.Closed,
    )
    fatal.foreach: error =>
      val connector = new Connector(failures = List(error))
      val result    = Reconnect.run(
        connector = connector,
        config    = ObsConfig(),
        policy    = policy,
        timing    = immediate,
        _ => (),
      ): (_, _) =>
        fail("fatal handshake must not invoke application")
      assertEquals(result, Left(error))
      assertEquals(connector.calls, 1)
    List(1001, 1006, 1011, 1012, 1013).foreach(code =>
      assert(ReconnectPolicy.retryable(error = ObsError.Transport(message = "transient", closeCode = Some(code))))
    )

  test("retry budget includes every connection attempt and terminates"):
    val connector = new Connector(failures = List.fill(8)(transient))
    val result    = Reconnect.run(
      connector = connector,
      config    = ObsConfig(),
      policy    = policy,
      timing    = immediate,
      _ => (),
    ): (_, _) =>
      ReconnectDecision.Complete(value = ())
    assertEquals(result, Left(transient))
    assertEquals(connector.calls, 4)

  test("event gap notice fires only when the retry budget still covers another attempt"):
    val connector = new Connector
    var notices   = Vector.empty[ReconnectNotice]
    val noRetries = ReconnectPolicy
      .create(maxRetries = 0, initialDelay = 1.millis, maxDelay = 4.millis, jitterFraction = 0.0)
      .toOption
      .get
    val result = Reconnect.run(
      connector = connector,
      config    = ObsConfig(),
      policy    = noRetries,
      timing    = immediate,
      notice => notices = notices :+ notice,
    ): (_, _) =>
      ReconnectDecision.Retry(cause = transient, desiredSubscriptions = ObsConfig().eventSubscriptions)
    assertEquals(result, Left(transient))
    assertEquals(connector.calls, 1)
    assert(!notices.exists(_.isInstanceOf[ReconnectNotice.RetryScheduled]))
    assert(!notices.exists(_.isInstanceOf[ReconnectNotice.EventGap]))

  test("healthy generations never exhaust the retry budget and restart the delay progression"):
    val connector = new Connector
    var delays    = Vector.empty[FiniteDuration]
    val timing    = ReconnectTiming(() => 0.5, delay => delays = delays :+ delay)
    val result    = Reconnect.run(
      connector = connector,
      config    = ObsConfig(),
      policy    = policy,
      timing    = timing,
      _ => (),
    ): (generation, _) =>
      if generation.value <= 5 then
        ReconnectDecision.Retry(cause       = transient, desiredSubscriptions = ObsConfig().eventSubscriptions)
      else ReconnectDecision.Complete(value = generation.value)
    assertEquals(result, Right(6L))
    assertEquals(connector.calls, 6)
    assertEquals(delays, Vector.fill(5)(1.millis))

  test("connection failures after a healthy generation consume a fresh budget"):
    val script    = List(Some(transient), Some(transient), None, Some(transient), Some(transient), Some(transient))
    val connector = new Connector(script = script)
    val result    = Reconnect.run(
      connector = connector,
      config    = ObsConfig(),
      policy    = policy,
      timing    = immediate,
      _ => (),
    ): (_, _) =>
      ReconnectDecision.Retry(cause = transient, desiredSubscriptions = ObsConfig().eventSubscriptions)
    assertEquals(result, Left(transient))
    assertEquals(connector.calls, 6)

  test("a non-retryable retry decision reports no event gap"):
    val connector = new Connector
    var notices   = Vector.empty[ReconnectNotice]
    val fatal     = ObsError.Authentication(message = "rejected")
    val result    = Reconnect.run(
      connector = connector,
      config    = ObsConfig(),
      policy    = policy,
      timing    = immediate,
      notice => notices = notices :+ notice,
    ): (_, _) =>
      ReconnectDecision.Retry(cause = fatal, desiredSubscriptions = ObsConfig().eventSubscriptions)
    assertEquals(result, Left(fatal))
    assertEquals(connector.calls, 1)
    assert(!notices.exists(_.isInstanceOf[ReconnectNotice.EventGap]))

  test("notice callback defects surface as internal errors within the either contract"):
    val connector = new Connector
    val result    = Reconnect.run(
      connector = connector,
      config    = ObsConfig(),
      policy    = policy,
      timing    = immediate,
      _ => throw new IllegalArgumentException("listener defect"),
    ): (_, _) =>
      ReconnectDecision.Complete(value = ())
    assertEquals(
      result,
      Left(ObsError.InternalError(message = "Reconnect notice callback failed: java.lang.IllegalArgumentException")),
    )
    assertEquals(connector.calls, 1)
    assert(connector.peers.head.closed)

  test("notice callbacks propagate interruption for cancellation"):
    val connector   = new Connector(failures = List(transient))
    val interrupted =
      try
        val _ = Reconnect.run(
          connector = connector,
          config    = ObsConfig(),
          policy    = policy,
          timing    = immediate,
          _ => throw new InterruptedException("cancelled"),
        ): (_, _) =>
          ReconnectDecision.Complete(value = ())
        false
      catch case _: InterruptedException => true
      finally
        val _ = Thread.interrupted()
    assert(interrupted)
    assertEquals(connector.calls, 1)

  test("application stop prevents retry even for a transient cause"):
    val connector = new Connector
    val result    = Reconnect.run(
      connector = connector,
      config    = ObsConfig(),
      policy    = policy,
      timing    = immediate,
      _ => (),
    ): (_, _) =>
      ReconnectDecision.Stop(error = transient)
    assertEquals(result, Left(transient))
    assertEquals(connector.calls, 1)

  test("invalid injected jitter stops before sleep and another connection"):
    val connector = new Connector(failures = List(transient))
    val timing    = ReconnectTiming(() => Double.NaN, _ => fail("must not sleep"))
    val result    = Reconnect.run(
      connector = connector,
      config    = ObsConfig(),
      policy    = policy,
      timing    = timing,
      _ => (),
    ): (_, _) =>
      ReconnectDecision.Complete(value = ())
    assert(result.left.exists(_.isInstanceOf[ObsError.InvalidConfiguration]))
    assertEquals(connector.calls, 1)

  test("cancellation interrupts backoff without opening another scope"):
    val connector = new Connector(failures = List(transient))
    val timing    = ReconnectTiming(() => 0.5, _ => ox.never)
    val result    = ox.timeoutOption(100.millis):
      Reconnect.run(
        connector = connector,
        config    = ObsConfig(),
        policy    = policy,
        timing    = timing,
        _ => (),
      ): (_, _) =>
        ReconnectDecision.Complete(value = ())
    assertEquals(result, None)
    assertEquals(connector.calls, 1)

  test("application failure propagates after closing the current scope without retry"):
    val connector = new Connector
    val _         = intercept[IllegalStateException]:
      Reconnect.run(
        connector = connector,
        config    = ObsConfig(),
        policy    = policy,
        timing    = immediate,
        _ => (),
      ): (_, _) =>
        throw new IllegalStateException("application defect")
    assertEquals(connector.calls, 1)
    assert(connector.peers.head.closed)

  test("invalid initial configuration never starts a connector"):
    val connector = new Connector
    assert(
      Reconnect
        .run(
          connector = connector,
          config    = ObsConfig(maxInFlight = 0),
          policy    = policy,
          timing    = immediate,
          _ => (),
        ): (_, _) =>
          ReconnectDecision.Complete(value = ())
        .isLeft
    )
    assertEquals(connector.calls, 0)

  test("live timing supplies valid randomness and interruptible sleep"):
    val live   = ReconnectTiming.live
    val sample = live.nextJitter()
    assert(sample >= 0.0 && sample < 1.0)
    live.sleep(Duration.Zero)
