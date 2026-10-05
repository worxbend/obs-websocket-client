package com.worxbend.obs.websocket.client.server

import com.worxbend.obs.websocket.client.ObsError
import _root_.sttp.client4.*
import _root_.sttp.model.StatusCode
import _root_.sttp.tapir.client.sttp4.SttpClientInterpreter
import _root_.sttp.tapir.server.stub4.TapirSyncStubInterpreter

class EndpointsSuite extends munit.FunSuite:
  private val httpEndpoint: _root_.sttp.model.Uri = uri"http://localhost"

  // sttp's close only shuts down the client executor and leaves JDK keep-alive
  // connections open, which would block the server's graceful stop for its full
  // default window. An owned java.net.http.HttpClient really closes them.
  private def withOwnedClient[A](use: SyncBackend => A): A =
    val jdk = java.net.http.HttpClient.newHttpClient()
    try use(_root_.sttp.client4.httpclient.HttpClientSyncBackend.usingClient(jdk))
    finally jdk.close()

  private def assertHealth(port: Int): Unit =
    withOwnedClient: client =>
      val response = SttpClientInterpreter()
        .toRequestThrowDecodeFailures(Endpoints.health, Some(uri"http://127.0.0.1:$port"))
        .apply(())
        .send(client)
      assertEquals(response.body, Right(Health("ok")))

  private def portReleased(port: Int): Boolean = scala.util
    .Try(new java.net.ServerSocket(port, 50, java.net.InetAddress.getByName("127.0.0.1")).close())
    .isSuccess

  private def backend(result: Either[ObsError, VersionInformation]): SyncBackend =
    backend(new ObsReadService:
      def version(): Either[ObsError, VersionInformation] = result)

  private def backend(service: ObsReadService): SyncBackend =
    TapirSyncStubInterpreter().whenServerEndpointsRunLogic(Endpoints.all(service)).backend()

  test("health endpoint returns a JSON liveness response without OBS"):
    val response = SttpClientInterpreter()
      .toRequestThrowDecodeFailures(Endpoints.health, Some(httpEndpoint))
      .apply(())
      .send(backend(Left(ObsError.Closed)))
    assertEquals(response.body, Right(Health("ok")))

  test("read-only version endpoint returns OBS versions"):
    val expected = VersionInformation("32.0.0", "5.6.3")
    val response = SttpClientInterpreter()
      .toRequestThrowDecodeFailures(Endpoints.version, Some(httpEndpoint))
      .apply(())
      .send(backend(Right(expected)))
    assertEquals(response.body, Right(expected))

  test("OBS failures produce a redacted 503 JSON error"):
    val response = SttpClientInterpreter()
      .toRequestThrowDecodeFailures(Endpoints.version, Some(httpEndpoint))
      .apply(())
      .send(backend(Left(ObsError.Authentication("sensitive upstream detail"))))
    assertEquals(
      (response.code, response.body),
      (StatusCode.ServiceUnavailable, Left(ApiFailure("OBS is unavailable")))
    )

  test("unchecked service defects produce the same redacted 503 JSON error"):
    val response = SttpClientInterpreter()
      .toRequestThrowDecodeFailures(Endpoints.version, Some(httpEndpoint))
      .apply(())
      .send(backend(new ObsReadService:
        def version(): Either[ObsError, VersionInformation] = throw new RuntimeException("sensitive upstream detail")))
    assertEquals(
      (response.code, response.body),
      (StatusCode.ServiceUnavailable, Left(ApiFailure("OBS is unavailable")))
    )

  test("Swagger serves HTML, JavaScript, CSS and OpenAPI over Netty"):
    ox.supervised:
      val service = new ObsReadService:
        def version(): Either[ObsError, VersionInformation] = Right(VersionInformation("32", "5"))
      val binding = ox.useInScope(
        _root_.sttp.tapir.server.netty.sync
          .NettySyncServer()
          .host("127.0.0.1")
          .port(0)
          .addEndpoints(Endpoints.all(service))
          .start()
      )(_.stop())
      val jdk = ox.useInScope(java.net.http.HttpClient.newHttpClient())(_.close())
      val client = _root_.sttp.client4.httpclient.HttpClientSyncBackend.usingClient(jdk)
      val base = s"http://127.0.0.1:${binding.port}"
      def get(path: String): Response[Either[String, String]] =
        basicRequest.get(_root_.sttp.model.Uri.unsafeParse(base + path)).send(client)
      def assertAsset(path: String, contentType: String): Unit =
        val response = get(path)
        assertEquals((path, response.code), (path, StatusCode.Ok))
        assert(
          response.contentType.exists(_.startsWith(contentType)),
          s"$path content type ${response.contentType} does not start with $contentType"
        )
        assert(response.body.toOption.exists(_.nonEmpty), s"$path body must not be empty")
      assertAsset("/docs/", "text/html")
      assertAsset("/docs/swagger-ui-bundle.js", "application/javascript")
      assertAsset("/docs/swagger-ui.css", "text/css")
      assertAsset("/docs/docs.yaml", "application/yaml")
      val openApi = get("/docs/docs.yaml").body.toOption.get
      assert(openApi.contains("openapi: 3"), "docs.yaml must be an OpenAPI 3 document")
      assert(openApi.contains("/health:"), "docs.yaml must declare the /health path")

  test("live service rejects invalid configuration without opening a socket"):
    val service = ObsReadService.live(com.worxbend.obs.websocket.client.ObsConfig(uri = "https://localhost"))
    assert(service.version().isLeft)

  test("application entrypoint logs the assigned port and releases it on cancellation"):
    import scala.concurrent.duration.*
    import java.nio.file.Files
    val source = Files.createTempFile("obs-server-test", ".conf")
    val logger = org.slf4j.LoggerFactory.getLogger(Main.getClass).asInstanceOf[ch.qos.logback.classic.Logger]
    try
      val _ = Files.writeString(source, """http { host = "127.0.0.1", port = 0 }, obs { url = "ws://127.0.0.1:1" }""")
      ox.timeout(12.seconds):
        ox.supervised:
          val startupPorts = ox.channels.Channel.buffered[Int](1)
          val appender = new ch.qos.logback.core.AppenderBase[ch.qos.logback.classic.spi.ILoggingEvent]:
            override def append(event: ch.qos.logback.classic.spi.ILoggingEvent): Unit =
              val message = event.getFormattedMessage
              if message.startsWith("Swagger UI: ") then
                startupPorts.send(java.net.URI.create(message.stripPrefix("Swagger UI: ")).getPort)
          appender.setContext(logger.getLoggerContext)
          appender.start()
          logger.addAppender(appender)
          try
            val application = ox.forkCancellable:
              Main.main(Array(source.toString))
            val port = startupPorts.receive()
            try assertHealth(port)
            finally
              val _ = application.cancel()
            assert(portReleased(port), s"Assigned port $port must be rebindable after cancellation")
          finally
            val _ = logger.detachAppender(appender)
    finally Files.delete(source)

  test("live service discovers and reads the version through an actual WebSocket"):
    import com.worxbend.obs.websocket.client.{ObsConfig, protocol}
    import com.worxbend.obs.websocket.client.transport.sttp.LocalWebSocketPeer
    // RFC 6455 close frame: the scoped connection must shut the WebSocket down.
    val closeFrameOpcode = 8
    // One version() call drives GetVersion twice: the client's internal probe during
    // connect, then the endpoint's own request.
    val getVersionRequests = 2
    def serve(socket: java.net.Socket): Unit =
      LocalWebSocketPeer.upgrade(socket)
      LocalWebSocketPeer.send(socket, """{"op":0,"d":{"obsWebSocketVersion":"5.6.3","rpcVersion":1}}""")
      val _ = LocalWebSocketPeer.receive(socket)
      LocalWebSocketPeer.send(socket, """{"op":2,"d":{"negotiatedRpcVersion":1}}""")
      for _ <- 1 to getVersionRequests do
        val request = protocol.Protocol.decode(LocalWebSocketPeer.receive(socket)._2).toOption.get
        val id = request.data.string("requestId").toOption.get
        LocalWebSocketPeer.send(
          socket,
          s"""{"op":7,"d":{"requestType":"GetVersion","requestId":"$id","requestStatus":{"result":true,"code":100},"responseData":{"obsVersion":"32.0.0","obsWebSocketVersion":"5.6.3","rpcVersion":1,"availableRequests":["GetVersion"],"supportedImageFormats":["png"],"platform":"linux","platformDescription":"test"}}}"""
        )
      assertEquals(LocalWebSocketPeer.receive(socket)._1, closeFrameOpcode)
    val result = LocalWebSocketPeer.run(serve): uri =>
      ObsReadService.live(ObsConfig(uri = uri)).version()
    assertEquals(result, Right(VersionInformation("32.0.0", "5.6.3")))

  test("live version read is bounded by one overall timeout"):
    import com.worxbend.obs.websocket.client.ObsConfig
    import com.worxbend.obs.websocket.client.transport.sttp.LocalWebSocketPeer
    import scala.concurrent.duration.*
    def serve(socket: java.net.Socket): Unit =
      LocalWebSocketPeer.upgrade(socket)
      // Stay silent past the client's overall budget; the read ends when the client gives up and closes.
      try
        val _ = LocalWebSocketPeer.receive(socket)
      catch case _: java.io.IOException | _: IllegalArgumentException => ()
    val result = LocalWebSocketPeer.run(serve): uri =>
      ObsReadService
        .live(ObsConfig(uri = uri, handshakeTimeout = 5.seconds, requestTimeout = 200.millis))
        .version()
    assertEquals(result, Left(ObsError.Timeout("version")))
