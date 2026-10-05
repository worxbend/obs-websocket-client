package com.worxbend.obs.websocket.client.transport.pekko

import com.worxbend.obs.websocket.client.protocol.Protocol
import com.worxbend.obs.websocket.client.transport.sttp.LocalWebSocketPeer
import com.worxbend.obs.websocket.client.{ObsConfig, ObsError}
import munit.FunSuite
import scala.concurrent.duration.*
import sttp.client4.pekkohttp.PekkoHttpBackend

class PekkoObsClientSuite extends FunSuite:
  private val noConnectionMessage: String = "No connection acquired"

  private val hello      = """{"op":0,"d":{"obsWebSocketVersion":"5.6.3","rpcVersion":1}}"""
  private val identified = """{"op":2,"d":{"negotiatedRpcVersion":1}}"""

  /** The owned system shuts its pools down aggressively, so teardown may end in an RST instead of a FIN. */
  private def assertTcpReleased(socket: java.net.Socket): Unit =
    try assertEquals(socket.getInputStream.read(), -1)
    catch case _: java.net.SocketException => ()

  test("real WebSocket handshake, fragmented Hello, version discovery, clean scoped close"):
    def serve(socket: java.net.Socket): Unit =
      LocalWebSocketPeer.upgrade(socket = socket)
      LocalWebSocketPeer.send(socket    = socket, text = hello.take(10), finalFragment = false)
      LocalWebSocketPeer.send(socket    = socket, text = hello.drop(10), opcode        = 0)
      val identify = LocalWebSocketPeer.receive(socket = socket)
      assertEquals(Protocol.decode(text = identify._2).toOption.get.op, 1)
      LocalWebSocketPeer.send(socket = socket, text = identified)
      val version = Protocol.decode(text = LocalWebSocketPeer.receive(socket = socket)._2).toOption.get
      val id      = version.data.string(name = "requestId").toOption.get
      LocalWebSocketPeer.send(
        socket = socket,
        text   =
          s"""{"op":7,"d":{"requestType":"GetVersion","requestId":"$id","requestStatus":{"result":true,"code":100},"responseData":{"availableRequests":["GetVersion"]}}}""",
      )
      assertEquals(LocalWebSocketPeer.receive(socket = socket)._1, 8)
      assertTcpReleased(socket = socket)
    val result = LocalWebSocketPeer.run(server = serve): uri =>
      PekkoObsClient.connect(config = ObsConfig(uri = uri))(use = _.metadata.availableRequests)
    assertEquals(result, Right(Set("GetVersion")))

  test("pings are answered by the pekko framework below the transport"):
    def serve(socket: java.net.Socket): Unit =
      LocalWebSocketPeer.upgrade(socket = socket)
      LocalWebSocketPeer.send(socket    = socket, text = "", opcode = 9)
      assertEquals(LocalWebSocketPeer.receive(socket = socket)._1, 10, "Pekko must auto-reply with a pong")
      LocalWebSocketPeer.send(socket = socket, text = hello)
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

  test("a peer error close preserves the status code it sent"):
    def serve(socket: java.net.Socket): Unit =
      LocalWebSocketPeer.upgrade(socket = socket)
      LocalWebSocketPeer.send(socket    = socket, text = hello)
      assertEquals(Protocol.decode(text = LocalWebSocketPeer.receive(socket = socket)._2).toOption.get.op, 1)
      LocalWebSocketPeer.send(socket = socket, text = identified)
      assertEquals(Protocol.decode(text = LocalWebSocketPeer.receive(socket = socket)._2).toOption.get.op, 6)
      // Code 3328 = 0x0D00; LocalWebSocketPeer payloads are UTF-8, so both bytes must stay ASCII.
      LocalWebSocketPeer.send(socket = socket, text = "\r\u0000", opcode = 8)
    val result = LocalWebSocketPeer.run(server = serve): uri =>
      PekkoObsClient.connect(config = ObsConfig(uri = uri))(use = _.rawRequest(requestType = "GetVersion"))
    assertEquals(result, Left(ObsError.Transport(message = "WebSocket closed", closeCode = Some(3328))))

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

  test("HTTP upgrade rejection is a typed redacted error"):
    def serve(socket: java.net.Socket): Unit =
      val _ = LocalWebSocketPeer.readHeaders(socket = socket)
      socket.getOutputStream.write(
        "HTTP/1.1 403 Forbidden\r\nContent-Length: 6\r\nConnection: close\r\n\r\nsecret".getBytes(
          java.nio.charset.StandardCharsets.UTF_8
        )
      )
      socket.getOutputStream.flush()
    val result = LocalWebSocketPeer.run(server = serve): uri =>
      PekkoObsClient.connect(config = ObsConfig(uri = uri))(_ => ())
    assertEquals(result, Left(ObsError.Transport(message = "WebSocket upgrade rejected")))

  test("non-handshake connection errors are typed and redacted"):
    val result = PekkoObsClient.connect(config = ObsConfig(uri = "ws://127.0.0.1:1"))(_ => ())
    assertEquals(result, Left(ObsError.Transport(message = "WebSocket connection failed")))

  test("connection deadline bounds acquisition of a silent upgrade and releases TCP"):
    def serve(socket: java.net.Socket): Unit =
      val _ = LocalWebSocketPeer.readHeaders(socket = socket)
      assertTcpReleased(socket = socket)
    val result = LocalWebSocketPeer.run(server = serve): uri =>
      PekkoObsClient.connect(config = ObsConfig(uri = uri, connectionTimeout = 100.millis))(_ => ())
    assertEquals(result, Left(ObsError.Timeout(operation = "connection")))

  test("the owned actor system terminates when the connection closes"):
    def threads: Int = Thread.getAllStackTraces.keySet.toArray.count(
      _.asInstanceOf[Thread].getName.contains("obs-websocket-client-pekko")
    )
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

  test("invalid configuration is rejected before connection"):
    assert(PekkoObsClient.connect(config = ObsConfig(uri = "https://localhost"))(_ => ()).isLeft)

  test("invalid transport options are rejected before connection"):
    val invalid = PekkoOptions(writeTimeout = Duration.Zero)
    assertEquals(
      PekkoObsClient.connect(config = ObsConfig(), options = invalid)(_ => ()),
      Left(ObsError.InvalidConfiguration(message = "Write deadline must be positive")),
    )

  test("injected backend entrypoint rejects malformed URI as configuration"):
    val backend = PekkoHttpBackend()
    try
      val result = PekkoObsClient.withBackend(
        backend = backend,
        config  = ObsConfig(uri = "ws://localhost:invalid"),
        () => fail(noConnectionMessage),
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

  test("residual sttp URI parse failures are configuration errors"):
    assertEquals(
      PekkoObsClient.websocketUri(config = ObsConfig(uri = "ws://localhost:invalid")),
      Left(ObsError.InvalidConfiguration(message = "Invalid WebSocket URI")),
    )
    assert(PekkoObsClient.websocketUri(config = ObsConfig()).isRight)

  test("injected backend remains usable after a rejected connection"):
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
          PekkoObsClient.withBackend(backend = backend, config = ObsConfig(uri = uri), () => fail(noConnectionMessage))(
            _ => ()
          )
        assertEquals(result, Left(ObsError.Transport(message = "WebSocket upgrade rejected")))
    finally
      val _ = system.terminate()
      val _ = PekkoRunner.await(future = system.whenTerminated)

  test("custom upgrade header reaches a real peer"):
    def reject(socket: java.net.Socket): Unit =
      assert(LocalWebSocketPeer.readHeaders(socket = socket).toLowerCase.contains("x-backend: pekko\r\n"))
      socket.getOutputStream.write(
        "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
          .getBytes(java.nio.charset.StandardCharsets.US_ASCII)
      )
      socket.getOutputStream.flush()
    val options = PekkoOptions(headers =
      com.worxbend.obs.websocket.client.transport.HandshakeHeaders
        .create(entries = Vector("X-Backend" -> "pekko"))
        .toOption
        .get
    )
    val result = LocalWebSocketPeer.run(server = reject): uri =>
      PekkoObsClient.connect(config = ObsConfig(uri = uri), options = options)(_ => ())
    assertEquals(result, Left(ObsError.Transport(message = "WebSocket upgrade rejected")))

  test("positive submillisecond deadlines construct the real backend without truncation errors"):
    val result =
      PekkoObsClient.connect(config = ObsConfig(uri = "ws://127.0.0.1:1", connectionTimeout = 1.nanosecond))(_ => ())
    assert(result.isLeft)
