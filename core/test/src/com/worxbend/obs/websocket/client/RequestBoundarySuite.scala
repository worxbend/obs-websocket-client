package com.worxbend.obs.websocket.client

import com.worxbend.obs.websocket.client.protocol.*
import munit.FunSuite
import ox.*
import ox.channels.{Actor, ActorRef, Channel}
import scala.concurrent.duration.*

class RequestBoundarySuite extends FunSuite:
  private def obj(fields: (String, JsonValue)*): JsonObject = JsonObject(fields = fields.toMap)
  private def str(value:  String): JsonValue                = JsonValue.Str(value = value)
  private def num(value:  Int): JsonValue                   = JsonValue.Num(value = BigDecimal(value))

  private class Harness(
    available:    Set[String] = Set("GetVersion"),
    dependencies: SessionDependencies = SessionDependencies(nextRequestId = () => "repeated"),
  )(using Ox):
    val outgoing: Channel[WireMessage]                            = Channel.buffered(8)
    val identified: Channel[Either[ObsError, ConnectionMetadata]] = Channel.buffered(1)
    val logic: ActorRef[SessionLogic]                             = Actor.create(new SessionLogic(
      config                 = ObsConfig(),
      authenticationPassword = None,
      outgoing               = outgoing,
      identified             = identified,
    ))
    val session: ObsSession = new ObsSession(
      metadata     = ConnectionMetadata("probe", 1, available),
      config       = ObsConfig(),
      dependencies = dependencies,
      logic        = logic,
    )
    def ready(): Unit =
      logic.ask(_.incoming(message =
        WireMessage(
          op   = 0,
          data = obj(
            "rpcVersion"          -> num(1),
            "obsWebSocketVersion" -> str("probe"),
          ),
        )
      )).discard
      outgoing.receive().discard
      logic.ask(_.incoming(message = WireMessage(op = 2, data = obj("negotiatedRpcVersion" -> num(1))))).discard
      identified.receive().discard
    def respond(message: WireMessage, code: Int = 100): Unit =
      val name     = message.data.string(name = "requestType").getOrElse("GetVersion")
      val id       = message.data.string(name = "requestId").toOption.get
      val response = obj(
        "requestType"   -> str(name),
        "requestStatus" -> obj("result" -> JsonValue.Bool(code == 100), "code" -> num(code)),
        "responseData"  -> JsonObject.empty,
      )
      val data = if message.op == 8 then obj("requestId" -> str(id), "results" -> JsonValue.Arr(Vector(response)))
      else
        obj(
          "requestId"     -> str(id),
          "requestType"   -> str(name),
          "requestStatus" -> obj("result" -> JsonValue.Bool(code == 100), "code" -> num(code)),
          "responseData"  -> JsonObject.empty,
        )
      logic.ask(_.incoming(message = WireMessage(op = if message.op == 8 then 9 else 7, data = data))).discard

  test("request budget expires during stalled registration, before closed-state admission"):
    supervised:
      val h       = new Harness
      val entered = Channel.buffered[Unit](1)
      val release = Channel.buffered[Unit](1)
      val blocker = fork(h.logic.ask: _ =>
        entered.send(())
        release.receive())
      entered.receive()
      val releaser = fork:
        sleep(250.millis)
        release.send(())
      val result = h.session.rawRequest(requestType = "Probe", options = RequestOptions(timeout = Some(20.millis)))
      assertEquals(result, Left(ObsError.Timeout(operation = "Probe")))
      releaser.join()
      blocker.join()
      assertEquals(h.logic.ask(_.statistics), SessionStats())
      assertEquals(h.outgoing.tryReceive(), None)

  test("elapsed ID preparation consumes the exchange budget before registration"):
    supervised:
      val clock = new java.util.concurrent.atomic.AtomicLong(0L)
      val h     = new Harness(dependencies =
        SessionDependencies(
          nextRequestId = () =>
            clock.set(1.second.toNanos)
            "slow-seed"
          ,
          nanoTime = () => clock.get(),
        )
      )
      assertEquals(
        h.session.rawRequest(requestType = "Probe", options = RequestOptions(timeout = Some(20.millis))),
        Left(ObsError.Timeout(operation = "Probe")),
      )
      assertEquals(h.outgoing.tryReceive(), None)
      assertEquals(h.logic.ask(_.statistics), SessionStats())

  test("readiness rejects a supported mutation capability without admitting it"):
    supervised:
      val h = new Harness(available = Set("StartRecord"))
      h.ready()
      assertEquals(
        h.session.requestWhenReady(request = protocol.requests.StartRecord()),
        Left(ObsError.InvalidConfiguration(message = "Readiness retries require a reviewed typed read request")),
      )
      assertEquals(h.outgoing.tryReceive(), None)

  test("batch snapshots extensible type and payload getters exactly once outside actor"):
    supervised:
      val h = new Harness
      h.ready()
      var typeReads = 0
      var dataReads = 0
      val request   = new Request[JsonObject]:
        def requestType: String =
          typeReads += 1
          "GetVersion"
        def requestData: JsonObject =
          dataReads += 1
          assertEquals(h.session.state, Right(ConnectionState.Ready))
          JsonObject.empty
        def decodeResponse(data: JsonObject): Either[ProtocolError, JsonObject] = Right(data)
      val responder = fork(h.respond(message = h.outgoing.receive()))
      assert(h.session.batch(requests = Vector(request)).isRight)
      responder.join()
      assertEquals((typeReads, dataReads), (1, 1))

  test("readiness whitelist and retries share one immutable read snapshot"):
    supervised:
      val h = new Harness(available = Set("GetVersion", "StartRecord"))
      h.ready()
      var typeReads = 0
      var dataReads = 0
      val changing  = new Request[JsonObject]:
        def requestType: String =
          typeReads += 1
          if typeReads == 1 then "GetVersion" else "StartRecord"
        def requestData: JsonObject =
          dataReads += 1
          JsonObject.empty
        def decodeResponse(data: JsonObject): Either[ProtocolError, JsonObject] = Right(data)
      val responder = fork:
        val first  = h.outgoing.receive()
        h.respond(message = first, code = 207)
        val second = h.outgoing.receive()
        h.respond(message = second)
        Vector(first, second).map(_.data.string(name = "requestType"))
      assertEquals(
        h.session.requestWhenReady(
          request = changing,
          policy  = ReadinessPolicy(maxAttempts = 2, delay = Duration.Zero),
        ),
        Right(JsonObject.empty),
      )
      assertEquals(responder.join(), Vector(Right("GetVersion"), Right("GetVersion")))
      assertEquals((typeReads, dataReads), (1, 1))

  test("constant injected ID seeds cannot reuse wire correlation IDs after timeout"):
    supervised:
      val h = new Harness
      h.ready()
      assertEquals(
        h.session.rawRequest(requestType = "GetVersion", options = RequestOptions(timeout = Some(20.millis))),
        Left(ObsError.Timeout(operation = "GetVersion")),
      )
      val old       = h.outgoing.receive()
      val responder = fork:
        val current = h.outgoing.receive()
        assertNotEquals(current.data.string(name = "requestId"), old.data.string(name = "requestId"))
        h.respond(message = old)
        h.respond(message = current)
      assertEquals(h.session.rawRequest(requestType = "GetVersion"), Right(JsonObject.empty))
      responder.join()

  test("stale queued message cannot borrow a replacement pending owner"):
    val outgoing   = Channel.buffered[WireMessage](8)
    val identified = Channel.buffered[Either[ObsError, ConnectionMetadata]](1)
    val logic      = new SessionLogic(
      config                 = ObsConfig(),
      authenticationPassword = None,
      outgoing               = outgoing,
      identified             = identified,
    )
    logic.incoming(message =
      WireMessage(op = 0, data = obj("rpcVersion" -> num(1), "obsWebSocketVersion" -> str("probe")))
    ).discard
    outgoing.receive().discard
    logic.incoming(message = WireMessage(op = 2, data = obj("negotiatedRpcVersion" -> num(1)))).discard
    identified.receive().discard
    val old          = WireMessage(op = 6, data = obj("requestId" -> str("same")))
    val current      = old.copy()
    val oldReply     = Channel.buffered[Either[ObsError, JsonObject]](1)
    val currentReply = Channel.buffered[Either[ObsError, JsonObject]](1)
    assertEquals(logic.register("same", "GetVersion", 7, old, oldReply), Right(()))
    logic.cancel(id = "same", owner = oldReply)
    assertEquals(logic.register("same", "GetVersion", 7, current, currentReply), Right(()))
    assert(!logic.canSend(message = old))
    assert(logic.canSend(message = current))
    logic.sendRejected(message = old, error = ObsError.MessageTooLarge(message = "stale"))
    assertEquals(currentReply.tryReceive(), None)
    logic.close()
