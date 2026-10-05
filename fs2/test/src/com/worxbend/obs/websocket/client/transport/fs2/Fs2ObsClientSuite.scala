package com.worxbend.obs.websocket.client.transport.fs2

import cats.effect.IO
import cats.effect.std.Dispatcher
import com.worxbend.obs.websocket.client.reconnect.{ReconnectDecision, ReconnectPolicy}
import com.worxbend.obs.websocket.client.transport.sttp.LocalWebSocketPeer
import com.worxbend.obs.websocket.client.{ObsConfig, ObsError}
import Fs2Runner.given
import munit.FunSuite
import scala.concurrent.duration.*
import sttp.client4.httpclient.fs2.HttpClientFs2Backend

/** fs2-specific client cases; the cross-backend wire contract lives in [[Fs2ContractSuite]]. */
class Fs2ObsClientSuite extends FunSuite:
  test("injected backend entrypoint rejects malformed URI as configuration"):
    val result = Fs2ObsClient.withBackend(
      backend = HttpClientFs2Backend.stub[IO],
      config  = ObsConfig(uri = "ws://localhost:invalid"),
      () => fail("No connection acquired"),
    )(_ => ())
    assertEquals(
      result,
      Left(
        ObsError.InvalidConfiguration(message = "Expected ws/wss URI with host, without credentials, query or fragment")
      ),
    )

  test("injected backend entrypoint validates transport options"):
    assertEquals(
      Fs2ObsClient.withBackend(
        backend = HttpClientFs2Backend.stub[IO],
        config  = ObsConfig(),
        () => fail("No connection acquired"),
        options = Fs2Options(writeTimeout = Duration.Zero),
      )(_ => ()),
      Left(ObsError.InvalidConfiguration(message = "Write deadline must be positive")),
    )

  test("residual sttp URI parse failures are configuration errors"):
    assertEquals(
      Fs2ObsClient.websocketUri(config = ObsConfig(uri = "ws://localhost:invalid")),
      Left(ObsError.InvalidConfiguration(message = "Invalid WebSocket URI")),
    )
    assert(Fs2ObsClient.websocketUri(config = ObsConfig()).isRight)

  test("client options apply proxy and TLS configuration to the owned JDK client"):
    val clientOptions = com.worxbend.obs.websocket.client.transport.JdkClientOptions(
      proxy      = Some(java.net.ProxySelector.of(new java.net.InetSocketAddress("127.0.0.1", 1))),
      sslContext = Some(javax.net.ssl.SSLContext.getDefault),
    )
    val result =
      Fs2ObsClient.connect(config = ObsConfig(uri = "ws://127.0.0.1:1"), clientOptions = clientOptions)(_ => ())
    assert(result.isLeft)

  test("reconnect entrypoint validates transport options before connecting"):
    val policy = ReconnectPolicy.create(maxRetries = 0).toOption.get
    assertEquals(
      Fs2ReconnectingObsClient.run(
        config  = ObsConfig(),
        policy  = policy,
        options = Fs2Options(writeTimeout = Duration.Zero),
      )((_, _) => ReconnectDecision.Complete(value = ())),
      Left(ObsError.InvalidConfiguration(message = "Write deadline must be positive")),
    )

  test("caller-owned backend and dispatcher remain usable after a rejected connection"):
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
          Fs2ObsClient.withBackend(
            backend = backend,
            config  = ObsConfig(uri = uri),
            () => fail("No connection acquired"),
          )(_ => ())
        assertEquals(result, Left(ObsError.Transport(message = "WebSocket upgrade rejected")))
