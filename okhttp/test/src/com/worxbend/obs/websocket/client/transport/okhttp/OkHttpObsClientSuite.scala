package com.worxbend.obs.websocket.client.transport.okhttp

import com.worxbend.obs.websocket.client.protocol.Protocol
import com.worxbend.obs.websocket.client.transport.sttp.{LocalWebSocketPeer, SttpOptions}
import com.worxbend.obs.websocket.client.{ObsConfig, ObsError}
import munit.FunSuite
import scala.concurrent.duration.*

class OkHttpObsClientSuite extends FunSuite:
  test("real WebSocket handshake, version discovery, clean scoped close"):
    def serve(socket: java.net.Socket): Unit =
      LocalWebSocketPeer.upgrade(socket = socket)
      LocalWebSocketPeer.send(socket = socket, text = """{"op":0,"d":{"obsWebSocketVersion":"5.7.0","rpcVersion":1}}""")
      assertEquals(Protocol.decode(text = LocalWebSocketPeer.receive(socket = socket)._2).toOption.get.op, 1)
      LocalWebSocketPeer.send(socket = socket, text = """{"op":2,"d":{"negotiatedRpcVersion":1}}""")
      val request = Protocol.decode(text = LocalWebSocketPeer.receive(socket = socket)._2).toOption.get
      val id      = request.data.string(name = "requestId").toOption.get
      LocalWebSocketPeer.send(
        socket = socket,
        text   =
          s"""{"op":7,"d":{"requestType":"GetVersion","requestId":"$id","requestStatus":{"result":true,"code":100},"responseData":{"availableRequests":["GetVersion"]}}}""",
      )
      assertEquals(LocalWebSocketPeer.receive(socket = socket)._1, 8)
      assertEquals(socket.getInputStream.read(), -1, "An unacknowledged Close must still release TCP")
    val result = LocalWebSocketPeer.run(server = serve): uri =>
      OkHttpObsClient.connect(config = ObsConfig(uri = uri))(use = _.metadata.availableRequests)
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
      OkHttpObsClient.connect(config = ObsConfig(uri = uri))(_ => ())
    // OkHttp reports rejection as a generic ProtocolException, not the JDK handshake exception.
    assertEquals(result, Left(ObsError.Transport(message = "WebSocket connection failed")))

  test("authentication required by the server without a password fails the handshake"):
    def serve(socket: java.net.Socket): Unit =
      LocalWebSocketPeer.upgrade(socket = socket)
      LocalWebSocketPeer.send(
        socket = socket,
        text   =
          """{"op":0,"d":{"obsWebSocketVersion":"5.7.0","rpcVersion":1,"authentication":{"challenge":"c","salt":"s"}}}""",
      )
      assertEquals(LocalWebSocketPeer.receive(socket = socket)._1, 8)
    val result = LocalWebSocketPeer.run(server = serve): uri =>
      OkHttpObsClient.connect(config = ObsConfig(uri = uri))(_ => ())
    assert(result.left.exists(_.isInstanceOf[ObsError.Authentication]))

  test("silent peer cannot retain a reader after the handshake deadline"):
    def serve(socket: java.net.Socket): Unit =
      LocalWebSocketPeer.upgrade(socket = socket)
      assertEquals(LocalWebSocketPeer.receive(socket = socket)._1, 8)
      assertEquals(socket.getInputStream.read(), -1, "An unacknowledged Close must still release TCP")
    val result = LocalWebSocketPeer.run(server = serve): uri =>
      OkHttpObsClient.connect(config = ObsConfig(uri = uri, handshakeTimeout = 100.millis))(_ => ())
    assertEquals(result, Left(ObsError.Timeout(operation = "handshake")))

  test("read idle deadline aborts a stalled connection and still releases TCP"):
    def serve(socket: java.net.Socket): Unit =
      LocalWebSocketPeer.upgrade(socket = socket)
      LocalWebSocketPeer.send(socket = socket, text = """{"op":0,"d":{"obsWebSocketVersion":"5.7.0","rpcVersion":1}}""")
      assertEquals(Protocol.decode(text = LocalWebSocketPeer.receive(socket = socket)._2).toOption.get.op, 1)
      LocalWebSocketPeer.send(socket = socket, text = """{"op":2,"d":{"negotiatedRpcVersion":1}}""")
      assertEquals(Protocol.decode(text = LocalWebSocketPeer.receive(socket = socket)._2).toOption.get.op, 6)
      assertEquals(socket.getInputStream.read(), -1, "A force-aborted connection must still release TCP")
    val options = SttpOptions(readIdleTimeout = Some(100.millis))
    val result  = LocalWebSocketPeer.run(server = serve): uri =>
      OkHttpObsClient.connect(config = ObsConfig(uri = uri), options = options)(use =
        _.rawRequest(requestType = "GetVersion")
      )
    assertEquals(result, Left(ObsError.Timeout(operation = "read")))

  test("invalid configuration is rejected before any client is built"):
    assert(OkHttpObsClient.connect(config = ObsConfig(uri = "https://localhost"))(_ => ()).isLeft)

  test("invalid transport options are rejected before any client is built"):
    val invalid = SttpOptions(writeTimeout = Duration.Zero)
    assertEquals(
      OkHttpObsClient.connect(config = ObsConfig(), options = invalid)(_ => ()),
      Left(ObsError.InvalidConfiguration(message = "Write deadline must be positive")),
    )

  test("invalid client options are rejected before any client is built"):
    val invalid = OkHttpClientOptions(readTimeout = Some(Duration.Zero))
    assertEquals(
      OkHttpObsClient.connect(config = ObsConfig(), clientOptions = invalid)(_ => ()),
      Left(ObsError.InvalidConfiguration(message = "OkHttp client deadlines must be positive")),
    )
