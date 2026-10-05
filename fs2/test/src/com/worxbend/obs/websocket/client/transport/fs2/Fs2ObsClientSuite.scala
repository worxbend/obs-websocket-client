package com.worxbend.obs.websocket.client.transport.fs2

import cats.effect.IO
import cats.effect.std.Dispatcher
import com.worxbend.obs.websocket.client.protocol.Protocol
import com.worxbend.obs.websocket.client.transport.sttp.LocalWebSocketPeer
import com.worxbend.obs.websocket.client.{ObsConfig, ObsError}
import Fs2Runner.given
import munit.FunSuite
import scala.concurrent.duration.*
import sttp.client4.httpclient.fs2.HttpClientFs2Backend

class Fs2ObsClientSuite extends FunSuite:
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
      Fs2ObsClient.connect(config = ObsConfig(uri = uri))(use = _.metadata.availableRequests)
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
      Fs2ObsClient.connect(config = ObsConfig(uri = uri))(_ => ())
    assertEquals(result, Left(ObsError.Transport(message = "WebSocket upgrade rejected")))

  test("non-handshake connection errors are typed and redacted"):
    val result = Fs2ObsClient.connect(config = ObsConfig(uri = "ws://127.0.0.1:1"))(_ => ())
    assertEquals(result, Left(ObsError.Transport(message = "WebSocket connection failed")))

  test("connection deadline bounds acquisition of a silent upgrade and releases TCP"):
    def serve(socket: java.net.Socket): Unit =
      val _ = LocalWebSocketPeer.readHeaders(socket = socket)
      assertEquals(socket.getInputStream.read(), -1, "An aborted acquisition must release TCP")
    val result = LocalWebSocketPeer.run(server = serve): uri =>
      Fs2ObsClient.connect(config = ObsConfig(uri = uri, connectionTimeout = 100.millis))(_ => ())
    assertEquals(result, Left(ObsError.Timeout(operation = "connection")))

  test("invalid configuration is rejected before connection"):
    assert(Fs2ObsClient.connect(config = ObsConfig(uri = "https://localhost"))(_ => ()).isLeft)

  test("invalid transport options are rejected before connection"):
    val invalid = Fs2Options(writeTimeout = Duration.Zero)
    assertEquals(
      Fs2ObsClient.connect(config = ObsConfig(), options = invalid)(_ => ()),
      Left(ObsError.InvalidConfiguration(message = "Write deadline must be positive")),
    )

  test("injected backend entrypoint rejects malformed URI as configuration"):
    val result = Fs2ObsClient.withBackend(
      backend = HttpClientFs2Backend.stub[IO],
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
      Fs2ObsClient.websocketUri(config = ObsConfig(uri = "ws://localhost:invalid")),
      Left(ObsError.InvalidConfiguration(message = "Invalid WebSocket URI")),
    )
    assert(Fs2ObsClient.websocketUri(config = ObsConfig()).isRight)

  test("injected backend remains usable after a rejected connection"):
    ox.resourceScope:
      val client                        = ox.useInScope(java.net.http.HttpClient.newHttpClient())(_.shutdownNow())
      val (dispatcher, releaseDispatch) = Dispatcher.parallel[IO].allocated.unsafeRunSync()
      val backend                       = ox.useInScope(
        HttpClientFs2Backend.usingClient[IO](client = client, dispatcher = dispatcher)
      )(_ => releaseDispatch.unsafeRunSync())
      def reject(socket: java.net.Socket): Unit =
        val _ = LocalWebSocketPeer.readHeaders(socket = socket)
        socket.getOutputStream.write(
          "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8)
        )
        socket.getOutputStream.flush()
      for _ <- 1 to 2 do
        val result = LocalWebSocketPeer.run(server = reject): uri =>
          Fs2ObsClient.withBackend(backend = backend, config = ObsConfig(uri = uri), () => fail(noConnectionMessage))(
            _ => ()
          )
        assertEquals(result, Left(ObsError.Transport(message = "WebSocket upgrade rejected")))

  test("custom upgrade header reaches a real peer"):
    def reject(socket: java.net.Socket): Unit =
      assert(LocalWebSocketPeer.readHeaders(socket = socket).toLowerCase.contains("x-backend: fs2\r\n"))
      socket.getOutputStream.write(
        "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
          .getBytes(java.nio.charset.StandardCharsets.US_ASCII)
      )
      socket.getOutputStream.flush()
    val options = Fs2Options(headers =
      com.worxbend.obs.websocket.client.transport.HandshakeHeaders
        .create(entries = Vector("X-Backend" -> "fs2"))
        .toOption
        .get
    )
    val result = LocalWebSocketPeer.run(server = reject): uri =>
      Fs2ObsClient.connect(config = ObsConfig(uri = uri), options = options)(_ => ())
    assertEquals(result, Left(ObsError.Transport(message = "WebSocket upgrade rejected")))

  test("positive submillisecond deadlines construct the real backend without truncation errors"):
    val result =
      Fs2ObsClient.connect(config = ObsConfig(uri = "ws://127.0.0.1:1", connectionTimeout = 1.nanosecond))(_ => ())
    assert(result.isLeft)
