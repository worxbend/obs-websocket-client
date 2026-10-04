package com.worxbend.obs.websocket.client.server

import com.worxbend.obs.websocket.client.ObsError
import _root_.sttp.client4.*
import _root_.sttp.model.StatusCode
import _root_.sttp.tapir.client.sttp4.SttpClientInterpreter
import _root_.sttp.tapir.server.stub4.TapirSyncStubInterpreter

class EndpointsSuite extends munit.FunSuite:
  // sttp's close only shuts down the client executor and leaves JDK keep-alive
  // connections open, which would block the server's graceful stop for its full
  // default window. An owned java.net.http.HttpClient really closes them.
  private def withOwnedClient[A](use: SyncBackend => A): A =
    val jdk = java.net.http.HttpClient.newHttpClient()
    try use(_root_.sttp.client4.httpclient.HttpClientSyncBackend.usingClient(jdk))
    finally jdk.close()

  private def awaitHealth(): Unit =
    import scala.concurrent.duration.*
    import ox.either.catching
    withOwnedClient: client =>
      var response = basicRequest
        .get(uri"http://127.0.0.1:8080/health")
        .send(client)
        .catching[_root_.sttp.client4.SttpClientException]
      while response.isLeft do
        ox.sleep(10.millis)
        response = basicRequest
          .get(uri"http://127.0.0.1:8080/health")
          .send(client)
          .catching[_root_.sttp.client4.SttpClientException]
      assertEquals(response.toOption.get.body, Right("{\"status\":\"ok\"}"))

  private def healthRequestFailsAfterStop(): Boolean =
    import ox.either.catching
    withOwnedClient: client =>
      basicRequest
        .get(uri"http://127.0.0.1:8080/health")
        .send(client)
        .catching[_root_.sttp.client4.SttpClientException]
        .isLeft

  private def portReleased: Boolean = scala.util
    .Try(new java.net.ServerSocket(8080, 50, java.net.InetAddress.getByName("127.0.0.1")).close())
    .isSuccess

  private def backend(result: Either[ObsError, VersionInformation]): SyncBackend =
    val service = new ObsReadService:
      def version(): Either[ObsError, VersionInformation] = result
    TapirSyncStubInterpreter().whenServerEndpointsRunLogic(Endpoints.all(service)).backend()

  test("health endpoint returns a JSON liveness response without OBS"):
    val response = SttpClientInterpreter()
      .toRequestThrowDecodeFailures(Endpoints.health, Some(uri"http://localhost"))
      .apply(())
      .send(backend(Left(ObsError.Closed)))
    assertEquals(response.body, Right(Health("ok")))

  test("read-only version endpoint returns OBS versions"):
    val expected = VersionInformation("32.0.0", "5.6.3")
    val response = SttpClientInterpreter()
      .toRequestThrowDecodeFailures(Endpoints.version, Some(uri"http://localhost"))
      .apply(())
      .send(backend(Right(expected)))
    assertEquals(response.body, Right(expected))

  test("OBS failures produce a redacted 503 JSON error"):
    val response = SttpClientInterpreter()
      .toRequestThrowDecodeFailures(Endpoints.version, Some(uri"http://localhost"))
      .apply(())
      .send(backend(Left(ObsError.Authentication("sensitive upstream detail"))))
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

  test("application entrypoint starts, serves health, and releases its port on cancellation"):
    import scala.concurrent.duration.*
    ox.timeout(12.seconds):
      ox.supervised:
        val application = ox.forkCancellable:
          ox.supervised:
            Main.run
        try awaitHealth()
        finally
          val _ = application.cancel()
        assert(healthRequestFailsAfterStop(), "health must be unreachable after cancellation")
        var released = portReleased
        val deadline = System.nanoTime() + 4.seconds.toNanos
        while !released && System.nanoTime() < deadline do
          ox.sleep(20.millis)
          released = portReleased
        assert(released, "port 8080 must be rebindable after the application is cancelled")

  test("live service discovers and reads the version through an actual WebSocket"):
    import com.worxbend.obs.websocket.client.{ObsConfig, protocol}
    import com.worxbend.obs.websocket.client.transport.sttp.LocalWebSocketPeer
    def serve(socket: java.net.Socket): Unit =
      LocalWebSocketPeer.upgrade(socket)
      LocalWebSocketPeer.send(socket, """{"op":0,"d":{"obsWebSocketVersion":"5.6.3","rpcVersion":1}}""")
      val _ = LocalWebSocketPeer.receive(socket)
      LocalWebSocketPeer.send(socket, """{"op":2,"d":{"negotiatedRpcVersion":1}}""")
      for _ <- 1 to 2 do
        val request = protocol.Protocol.decode(LocalWebSocketPeer.receive(socket)._2).toOption.get
        val id = request.data.string("requestId").toOption.get
        LocalWebSocketPeer.send(
          socket,
          s"""{"op":7,"d":{"requestType":"GetVersion","requestId":"$id","requestStatus":{"result":true,"code":100},"responseData":{"obsVersion":"32.0.0","obsWebSocketVersion":"5.6.3","rpcVersion":1,"availableRequests":["GetVersion"],"supportedImageFormats":["png"],"platform":"linux","platformDescription":"test"}}}"""
        )
      assertEquals(LocalWebSocketPeer.receive(socket)._1, 8)
    val result = LocalWebSocketPeer.run(serve): uri =>
      ObsReadService.live(ObsConfig(uri = uri)).version()
    assertEquals(result, Right(VersionInformation("32.0.0", "5.6.3")))
