package com.worxbend.obs.websocket.client.transport.sttp

import com.worxbend.obs.websocket.client.{ObsConfig, ObsError}
import com.worxbend.obs.websocket.client.protocol.Protocol
import munit.FunSuite
import scala.concurrent.duration.*
import _root_.sttp.client4.testing.WebSocketSyncBackendStub

class SttpClientSuite extends FunSuite:
  private val noConnectionMessage: String = "No connection acquired"

  private val hello      = """{"op":0,"d":{"obsWebSocketVersion":"5.6.3","rpcVersion":1}}"""
  private val identified = """{"op":2,"d":{"negotiatedRpcVersion":1}}"""

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
      assertEquals(socket.getInputStream.read(), -1, "An unacknowledged Close must still release TCP")
    val result = LocalWebSocketPeer.run(server = serve): uri =>
      SttpObsClient.connect(config = ObsConfig(uri = uri))(use = _.metadata.availableRequests)
    assertEquals(result, Right(Set("GetVersion")))

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
      SttpObsClient.connect(config = ObsConfig(uri = uri))(_ => ())
    assertEquals(result, Left(ObsError.Transport(message = "WebSocket upgrade rejected")))

  test("silent peer cannot retain a reader after the handshake deadline"):
    def serve(socket: java.net.Socket): Unit =
      LocalWebSocketPeer.upgrade(socket = socket)
      assertEquals(LocalWebSocketPeer.receive(socket = socket)._1, 8)
      assertEquals(socket.getInputStream.read(), -1, "An unacknowledged Close must still release TCP")
    val result = LocalWebSocketPeer.run(server = serve): uri =>
      SttpObsClient.connect(config = ObsConfig(uri = uri, handshakeTimeout = 100.millis))(_ => ())
    assertEquals(result, Left(ObsError.Timeout(operation = "handshake")))

  test("invalid configuration is rejected before connection"):
    assert(SttpObsClient.connect(config = ObsConfig(uri = "https://localhost"))(_ => ()).isLeft)

  test("total budget entrypoint validates config and transport options before budgeting"):
    val config = ObsConfig(uri = "https://localhost")
    assertEquals(
      SttpObsClient.connectWithin(config = config, budget = 1.nanosecond)(_ => fail(noConnectionMessage)),
      Left(config.validate.swap.toOption.get),
    )
    val options = SttpOptions(writeTimeout = Duration.Zero)
    assertEquals(
      SttpObsClient.connectWithin(config = ObsConfig(), budget = 1.nanosecond, options = options)(_ =>
        fail(noConnectionMessage)
      ),
      Left(options.validate.swap.toOption.get),
    )

  test("total budget entrypoint rejects nonpositive budget before runtime allocation"):
    assertEquals(
      SttpObsClient.connectWithin(config = ObsConfig(), budget = Duration.Zero)(_ => fail(noConnectionMessage)),
      Left(ObsError.InvalidConfiguration(message = "Operation budget must be positive")),
    )

  test("exhausted total budget never starts acquisition or invokes the callback"):
    assertEquals(
      SttpObsClient.connectWithin(config = ObsConfig(uri = "ws://127.0.0.1:1"), budget = 1.nanosecond)(_ =>
        fail(noConnectionMessage)
      ),
      Left(ObsError.Timeout(operation = "operation")),
    )

  private def discover(socket: java.net.Socket): Unit =
    LocalWebSocketPeer.upgrade(socket = socket)
    LocalWebSocketPeer.send(socket    = socket, text = hello)
    assertEquals(Protocol.decode(text = LocalWebSocketPeer.receive(socket = socket)._2).toOption.get.op, 1)
    LocalWebSocketPeer.send(socket = socket, text = identified)
    val request = Protocol.decode(text = LocalWebSocketPeer.receive(socket = socket)._2).toOption.get
    assertEquals(request.data.string(name = "requestType"), Right("GetVersion"))
    val id = request.data.string(name = "requestId").toOption.get
    LocalWebSocketPeer.send(
      socket = socket,
      text   =
        s"""{"op":7,"d":{"requestType":"GetVersion","requestId":"$id","requestStatus":{"result":true,"code":100},"responseData":{"availableRequests":["GetVersion"]}}}""",
    )

  test("total budget entrypoint completes real discovery and releases the owned connection"):
    def serve(socket: java.net.Socket): Unit =
      discover(socket = socket)
      assertEquals(LocalWebSocketPeer.receive(socket = socket)._1, 8)
      assertEquals(socket.getInputStream.read(), -1)
    val result = LocalWebSocketPeer.run(server = serve): uri =>
      SttpObsClient.connectWithin(config = ObsConfig(uri = uri, shutdownTimeout = 50.millis), budget = 3.seconds)(
        use = _.metadata.availableRequests
      )
    assertEquals(result, Right(Set("GetVersion")))

  test("total budget clips a stalled native upgrade and releases TCP"):
    def serve(socket: java.net.Socket): Unit =
      val _ = LocalWebSocketPeer.readHeaders(socket = socket)
      assertEquals(socket.getInputStream.read(), -1, "Upgrade timeout must physically release the connection")
    val result = LocalWebSocketPeer.run(server = serve): uri =>
      val started  = System.nanoTime()
      val response = SttpObsClient.connectWithin(
        config = ObsConfig(uri = uri, connectionTimeout = 5.seconds, shutdownTimeout = 50.millis),
        budget = 500.millis,
      )(_ => fail(noConnectionMessage))
      assert((System.nanoTime() - started).nanos < 2.seconds, "Native upgrade must not use the 5-second timeout")
      response
    assertEquals(result, Left(ObsError.Timeout(operation = "operation")))

  test("total budget interrupts callback work after discovery and closes the connection"):
    def serve(socket: java.net.Socket): Unit =
      discover(socket = socket)
      assertEquals(LocalWebSocketPeer.receive(socket = socket)._1, 8)
      assertEquals(socket.getInputStream.read(), -1)
    val result = LocalWebSocketPeer.run(server = serve): uri =>
      SttpObsClient.connectWithin(config = ObsConfig(uri = uri, shutdownTimeout = 50.millis), budget = 800.millis):
        session =>
          assertEquals(session.metadata.availableRequests, Set("GetVersion"))
          ox.sleep(5.seconds)
          fail("Callback must be interrupted before its independent work completes")
    assertEquals(result, Left(ObsError.Timeout(operation = "operation")))

  test("remaining operation budget rejects an already expired deadline"):
    assertEquals(
      SttpObsClient.remaining(deadline = Deadline.now - 1.second),
      Left(ObsError.Timeout(operation = "operation")),
    )

  test("remaining operation budget retains a future deadline without renewing it"):
    val remaining = SttpObsClient.remaining(deadline = Deadline.now + 1.minute).toOption.get
    assert(remaining > Duration.Zero)
    assert(remaining <= 1.minute)

  test("native acquisition uses the clipped timeout of an already constructed JDK client"):
    ox.resourceScope:
      val client = ox.useInScope(
        java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build()
      )(_.shutdownNow())
      val backend = _root_.sttp.client4.httpclient.HttpClientSyncBackend.usingClient(
        new AcquisitionHttpClient(delegate = client, acquisitionTimeout = 200.millis)
      )
      def serve(socket: java.net.Socket): Unit =
        val _ = LocalWebSocketPeer.readHeaders(socket = socket)
        assertEquals(socket.getInputStream.read(), -1, "Native acquisition expiry must release stalled TCP")
      val result = LocalWebSocketPeer.run(server = serve): uri =>
        val started  = System.nanoTime()
        val response = SttpObsClient.withBackend(
          backend         = backend,
          config          = ObsConfig(uri = uri, connectionTimeout = 100.millis),
          abortConnection = () => client.shutdownNow(),
        )(_ => fail(noConnectionMessage))
        val elapsed = (System.nanoTime() - started).nanos
        assert(elapsed < 1.second, s"Shielded acquisition used the original client's 5-second timeout: $elapsed")
        response
      assertEquals(result, Left(ObsError.Timeout(operation = "connection")))

  test("injected backend entrypoint rejects malformed URI as configuration"):
    val result = SttpObsClient.withBackend(
      backend = WebSocketSyncBackendStub,
      config  = ObsConfig(uri = "ws://localhost:invalid"),
      () => fail(noConnectionMessage),
    )(_ => ())
    assertEquals(
      result,
      Left(
        ObsError.InvalidConfiguration(message = "Expected ws/wss URI with host, without credentials, query or fragment")
      ),
    )

  test("residual sttp URI parse failures are configuration errors"):
    assertEquals(
      SttpObsClient.websocketUri(config = ObsConfig(uri = "ws://localhost:invalid")),
      Left(ObsError.InvalidConfiguration(message = "Invalid WebSocket URI")),
    )
    assert(SttpObsClient.websocketUri(config = ObsConfig()).isRight)

  test("connection deadline waits for bounded backend acquisition cleanup"):
    val backend = WebSocketSyncBackendStub.whenAnyRequest.thenRespondF: (_: _root_.sttp.client4.GenericRequest[?, ?]) =>
      ox.sleep(50.millis)
      _root_.sttp.client4.testing.ResponseStub.exact(Left("late rejection"))
    val result = SttpObsClient.withBackend(
      backend = backend,
      config  = ObsConfig(connectionTimeout = 10.millis),
      () => fail(noConnectionMessage),
    )(_ => ())
    assertEquals(result, Left(ObsError.Timeout(operation = "connection")))

  test("non-handshake connection errors are typed and redacted"):
    val backend = WebSocketSyncBackendStub.whenAnyRequest.thenThrow(new java.net.ConnectException("secret"))
    val result  =
      SttpObsClient.withBackend(backend = backend, config = ObsConfig(), () => fail(noConnectionMessage))(_ => ())
    assertEquals(result, Left(ObsError.Transport(message = "WebSocket connection failed")))

  test("injected backend remains usable after a rejected connection"):
    ox.resourceScope:
      val backend = ox.useInScope(_root_.sttp.client4.httpclient.HttpClientSyncBackend())(_.close())
      def reject(socket: java.net.Socket): Unit =
        val _ = LocalWebSocketPeer.readHeaders(socket = socket)
        socket.getOutputStream.write(
          "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8)
        )
        socket.getOutputStream.flush()
      for _ <- 1 to 2 do
        val result = LocalWebSocketPeer.run(server = reject): uri =>
          SttpObsClient.withBackend(backend = backend, config = ObsConfig(uri = uri), () => fail(noConnectionMessage))(
            _ => ()
          )
        assertEquals(result, Left(ObsError.Transport(message = "WebSocket upgrade rejected")))

  test("positive submillisecond deadlines construct the real backend without truncation errors"):
    val result =
      SttpObsClient.connect(config = ObsConfig(uri = "ws://127.0.0.1:1", connectionTimeout = 1.nanosecond))(_ => ())
    assert(result.isLeft)
