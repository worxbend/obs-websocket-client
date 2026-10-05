package com.worxbend.obs.websocket.client.transport.sttp

import com.worxbend.obs.websocket.client.{ObsConfig, ObsError}
import java.net.{InetSocketAddress, ProxySelector}
import javax.net.ssl.SSLContext
import munit.FunSuite
import scala.concurrent.duration.*

class SttpOptionsSuite extends FunSuite:
  private val testHeaderName: String = "X-Test"

  test("upgrade headers accept HTTP token names and visible ASCII values"):
    val headers = HandshakeHeaders.create(entries = Vector("Authorization" -> "Bearer secret", "X-Trace" -> "\tvalue"))
    assertEquals(headers.toOption.get.entries.size, 2)
    assertEquals(headers.toOption.get.toString, "HandshakeHeaders(<redacted>)")
    assertEquals(HandshakeHeaders.empty.entries, Vector.empty)

  test("upgrade headers reject injection, invalid names, and reserved handshake fields"):
    val invalid = Vector(
      ""                       -> "value",
      "Bad Name"               -> "value",
      testHeaderName           -> "secret\r\nInjected: true",
      testHeaderName           -> "secret\u0000",
      testHeaderName           -> "secret\u007f",
      testHeaderName           -> "é",
      "Connection"             -> "upgrade",
      "Upgrade"                -> "websocket",
      "HOST"                   -> "other.example",
      "Content-Length"         -> "0",
      "Transfer-Encoding"      -> "chunked",
      "Expect"                 -> "100-continue",
      "Sec-WebSocket-Protocol" -> "other",
    )
    invalid.foreach: entry =>
      assertEquals(
        HandshakeHeaders.create(entries = Vector(entry)),
        Left(ObsError.InvalidConfiguration(message = "Invalid or reserved WebSocket handshake header")),
      )

  test("write deadline validates before backend acquisition and renders no header values"):
    val options =
      SttpOptions(headers = HandshakeHeaders.create(entries = Vector("Authorization" -> "secret")).toOption.get)
    assertEquals(options.validate, Right(options))
    assertEquals(options.toString, "SttpOptions(writeTimeout=10 seconds, headers=<redacted>, readIdleTimeout=None)")
    val invalid = SttpOptions(writeTimeout = Duration.Zero)
    val error   = Left(ObsError.InvalidConfiguration(message = "Write deadline must be positive"))
    assertEquals(invalid.validate, error)
    assertEquals(SttpObsClient.connect(config = ObsConfig(), options = invalid)(_ => ()), error)
    assertEquals(
      SttpObsClient.withBackend(
        backend = _root_.sttp.client4.testing.WebSocketSyncBackendStub,
        config  = ObsConfig(),
        () => (),
        options = invalid,
      )(_ => ()),
      error,
    )
    assertEquals(
      ReconnectingObsClient.run(
        config  = ObsConfig(),
        policy  = ReconnectPolicy.create().toOption.get,
        options = invalid,
      )((_, _) => ReconnectDecision.Complete(value = ())),
      error,
    )

  test("read idle deadline is opt-in and validates positivity"):
    assertEquals(SttpOptions().readIdleTimeout, None)
    val liveness = SttpOptions(readIdleTimeout = Some(30.seconds))
    assertEquals(liveness.validate, Right(liveness))
    assert(liveness.toString.contains("readIdleTimeout=Some(30 seconds)"))
    val invalid = Left(ObsError.InvalidConfiguration(message = "Read idle deadline must be positive"))
    assertEquals(SttpOptions(readIdleTimeout = Some(Duration.Zero)).validate, invalid)
    assertEquals(SttpOptions(readIdleTimeout = Some((-1).second)).validate, invalid)

  test("private JDK client applies proxy trust context and precise connection deadline"):
    val proxy   = ProxySelector.of(new InetSocketAddress("127.0.0.1", 8080))
    val tls     = SSLContext.getDefault
    val options = JdkClientOptions(proxy = Some(proxy), sslContext = Some(tls))
    val client  = options.build(connectionTimeout = 1.nanosecond)
    try
      assertEquals(client.proxy().get(), proxy)
      assertEquals(client.sslContext(), tls)
      assertEquals(client.connectTimeout().get(), java.time.Duration.ofNanos(1))
      assertEquals(options.toString, "JdkClientOptions(<redacted>)")
    finally client.shutdownNow()

  test("custom upgrade header reaches a real peer without redirects or response disclosure"):
    def reject(socket: java.net.Socket): Unit =
      val headers = LocalWebSocketPeer.readHeaders(socket = socket)
      assert(headers.toLowerCase.contains("authorization: bearer secret\r\n"))
      socket.getOutputStream.write(
        "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
          .getBytes(java.nio.charset.StandardCharsets.US_ASCII)
      )
      socket.getOutputStream.flush()
    val options =
      SttpOptions(headers = HandshakeHeaders.create(entries = Vector("Authorization" -> "Bearer secret")).toOption.get)
    val result = LocalWebSocketPeer.run(server = reject): uri =>
      SttpObsClient.connect(config = ObsConfig(uri = uri), options = options)(_ => ())
    assertEquals(result, Left(ObsError.Transport(message = "WebSocket upgrade rejected")))

  test("reconnect entrypoint propagates transport headers to its owned connection"):
    def reject(socket: java.net.Socket): Unit =
      assert(LocalWebSocketPeer.readHeaders(socket = socket).toLowerCase.contains("x-reconnect: retained\r\n"))
      socket.getOutputStream.write(
        "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
          .getBytes(java.nio.charset.StandardCharsets.US_ASCII)
      )
      socket.getOutputStream.flush()
    val options =
      SttpOptions(headers = HandshakeHeaders.create(entries = Vector("X-Reconnect" -> "retained")).toOption.get)
    val result = LocalWebSocketPeer.run(server = reject): uri =>
      ReconnectingObsClient.run(
        config  = ObsConfig(uri = uri),
        policy  = ReconnectPolicy.create(maxRetries = 0).toOption.get,
        options = options,
      )((_, _) => ReconnectDecision.Complete(value = ()))
    assertEquals(result, Left(ObsError.Transport(message = "WebSocket upgrade rejected")))
