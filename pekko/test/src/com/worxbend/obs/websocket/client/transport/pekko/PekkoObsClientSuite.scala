package com.worxbend.obs.websocket.client.transport.pekko

import com.worxbend.obs.websocket.client.protocol.Protocol
import com.worxbend.obs.websocket.client.reconnect.{ReconnectDecision, ReconnectPolicy}
import com.worxbend.obs.websocket.client.transport.sttp.LocalWebSocketPeer
import com.worxbend.obs.websocket.client.{ObsConfig, ObsError}
import munit.FunSuite
import scala.concurrent.duration.*
import sttp.client4.pekkohttp.PekkoHttpBackend

/** Pekko-specific client cases; the cross-backend wire contract lives in [[PekkoContractSuite]]. */
class PekkoObsClientSuite extends FunSuite:
  private val hello      = """{"op":0,"d":{"obsWebSocketVersion":"5.6.3","rpcVersion":1}}"""
  private val identified = """{"op":2,"d":{"negotiatedRpcVersion":1}}"""

  test("injected backend entrypoint rejects malformed URI as configuration"):
    val backend = PekkoHttpBackend()
    try
      val result = PekkoObsClient.withBackend(
        backend = backend,
        config  = ObsConfig(uri = "ws://localhost:invalid"),
        () => fail("No connection acquired"),
      )(_ => ())
      assertEquals(
        result,
        Left(
          ObsError.InvalidConfiguration(message =
            "Expected ws/wss URI with host, without credentials, query or fragment"
          )
        ),
      )
    finally
      val _ = PekkoRunner.await(future = backend.close())

  test("injected backend entrypoint validates transport options"):
    val backend = PekkoHttpBackend()
    try
      assertEquals(
        PekkoObsClient.withBackend(
          backend = backend,
          config  = ObsConfig(),
          () => fail("No connection acquired"),
          options = PekkoOptions(writeTimeout = Duration.Zero),
        )(_ => ()),
        Left(ObsError.InvalidConfiguration(message = "Write deadline must be positive")),
      )
    finally
      val _ = PekkoRunner.await(future = backend.close())

  test("residual sttp URI parse failures are configuration errors"):
    assertEquals(
      PekkoObsClient.websocketUri(config = ObsConfig(uri = "ws://localhost:invalid")),
      Left(ObsError.InvalidConfiguration(message = "Invalid WebSocket URI")),
    )
    assert(PekkoObsClient.websocketUri(config = ObsConfig()).isRight)

  test("reconnect entrypoint validates transport options before connecting"):
    val policy = ReconnectPolicy.create(maxRetries = 0).toOption.get
    assertEquals(
      PekkoReconnectingObsClient.run(
        config  = ObsConfig(),
        policy  = policy,
        options = PekkoOptions(writeTimeout = Duration.Zero),
      )((_, _) => ReconnectDecision.Complete(value = ())),
      Left(ObsError.InvalidConfiguration(message = "Write deadline must be positive")),
    )

  test("invalid options build no actor system"):
    def threads: Int = Thread.getAllStackTraces.keySet.toArray
      .count(_.asInstanceOf[Thread].getName.contains("obs-websocket-client-pekko"))
    assertEquals(threads, 0)
    val result = PekkoObsClient.connect(
      config  = ObsConfig(),
      options = PekkoOptions(writeTimeout = Duration.Zero),
    )(_ => ())
    assertEquals(result, Left(ObsError.InvalidConfiguration(message = "Write deadline must be positive")))
    assertEquals(threads, 0, "Validation must precede actor system allocation")

  test("owned actor system names are unique"):
    val first  = PekkoObsClient.newSystemName()
    val second = PekkoObsClient.newSystemName()
    assert(first != second)
    assert(first.startsWith("obs-websocket-client-pekko-"))

  test("a wedged actor system does not pin the closing scope past the termination timeout"):
    val system = org.apache.pekko.actor.ActorSystem(name = "pekko-wedged")
    val gate   = new java.util.concurrent.Semaphore(0)
    val _      = system.actorOf(
      org.apache.pekko.actor.Props(new org.apache.pekko.actor.Actor:
        override def postStop(): Unit           = gate.acquire()
        def receive: PartialFunction[Any, Unit] = { case _ => () })
    )
    val start = System.nanoTime()
    PekkoObsClient.shutdownSystem(system = system, timeout = 100.millis)
    assert(System.nanoTime() - start < 5.seconds.toNanos, "An expired termination wait must proceed")
    gate.release(10)
    val _ = PekkoRunner.await(future = system.whenTerminated)

  test("the owned actor system terminates when the connection closes"):
    def threads: Int = Thread.getAllStackTraces.keySet.toArray
      .count(_.asInstanceOf[Thread].getName.contains("obs-websocket-client-pekko"))
    def serve(socket: java.net.Socket): Unit =
      LocalWebSocketPeer.upgrade(socket = socket)
      LocalWebSocketPeer.send(socket    = socket, text = hello)
      assertEquals(Protocol.decode(text = LocalWebSocketPeer.receive(socket = socket)._2).toOption.get.op, 1)
      LocalWebSocketPeer.send(socket = socket, text = identified)
      val discovery = Protocol.decode(text = LocalWebSocketPeer.receive(socket = socket)._2).toOption.get
      val id        = discovery.data.string(name = "requestId").toOption.get
      LocalWebSocketPeer.send(
        socket = socket,
        text   =
          s"""{"op":7,"d":{"requestType":"GetVersion","requestId":"$id","requestStatus":{"result":true,"code":100},"responseData":{"availableRequests":[]}}}""",
      )
      assertEquals(LocalWebSocketPeer.receive(socket = socket)._1, 8)
    val result = LocalWebSocketPeer.run(server = serve): uri =>
      PekkoObsClient.connect(config = ObsConfig(uri = uri))(_ => ())
    assertEquals(result, Right(()))
    val deadline = 2.seconds.fromNow
    while threads > 0 && deadline.hasTimeLeft() do Thread.sleep(5)
    assertEquals(threads, 0, "The owned actor system must terminate and release its threads")

  test("a peer normal close completes with the synthetic code 1000"):
    def serve(socket: java.net.Socket): Unit =
      LocalWebSocketPeer.upgrade(socket = socket)
      LocalWebSocketPeer.send(socket    = socket, text = hello)
      assertEquals(Protocol.decode(text = LocalWebSocketPeer.receive(socket = socket)._2).toOption.get.op, 1)
      LocalWebSocketPeer.send(socket = socket, text = identified)
      assertEquals(Protocol.decode(text = LocalWebSocketPeer.receive(socket = socket)._2).toOption.get.op, 6)
      LocalWebSocketPeer.send(socket = socket, text = "", opcode = 8)
    val result = LocalWebSocketPeer.run(server = serve): uri =>
      PekkoObsClient.connect(config = ObsConfig(uri = uri))(use = _.rawRequest(requestType = "GetVersion"))
    assertEquals(result, Left(ObsError.Transport(message = "WebSocket closed", closeCode = Some(1000))))

  test("caller-owned backend and actor system remain usable after a rejected connection"):
    val system = org.apache.pekko.actor.ActorSystem(name = "pekko-caller-owned")
    try
      val backend                               = PekkoHttpBackend.usingActorSystem(actorSystem = system)
      def reject(socket: java.net.Socket): Unit =
        val _ = LocalWebSocketPeer.readHeaders(socket = socket)
        socket.getOutputStream.write(
          "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8)
        )
        socket.getOutputStream.flush()
      for _ <- 1 to 2 do
        val result = LocalWebSocketPeer.run(server = reject): uri =>
          PekkoObsClient.withBackend(
            backend = backend,
            config  = ObsConfig(uri = uri),
            () => fail("No connection acquired"),
          )(_ => ())
        assertEquals(result, Left(ObsError.Transport(message = "WebSocket upgrade rejected")))
    finally
      val _ = system.terminate()
      val _ = PekkoRunner.await(future = system.whenTerminated)
