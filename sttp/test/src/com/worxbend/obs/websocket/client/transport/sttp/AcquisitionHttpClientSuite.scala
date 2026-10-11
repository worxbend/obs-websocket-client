package com.worxbend.obs.websocket.client.transport.sttp

import java.net.{Authenticator, CookieHandler, CookieManager, InetSocketAddress, ProxySelector, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Optional
import java.util.concurrent.{Executor, TimeUnit}
import javax.net.ssl.SSLContext
import scala.concurrent.duration.*

class AcquisitionHttpClientSuite extends munit.FunSuite:
  test("acquisition view overrides only the timeout and preserves configured JDK options"):
    ox.resourceScope:
      val cookies       = new CookieManager()
      val proxy         = ProxySelector.of(new InetSocketAddress("127.0.0.1", 9000))
      val authenticator = new Authenticator():
        override def getPasswordAuthentication(): java.net.PasswordAuthentication =
          fail("Configuration access must not request credentials")
      val executor   = ox.useInScope(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())(_.close())
      val tls        = SSLContext.getDefault
      val parameters = tls.getDefaultSSLParameters
      parameters.setProtocols(Array("TLSv1.3"))
      val delegate = ox.useInScope(
        HttpClient.newBuilder()
          .connectTimeout(java.time.Duration.ofSeconds(5))
          .cookieHandler(cookies)
          .followRedirects(HttpClient.Redirect.ALWAYS)
          .proxy(proxy)
          .sslContext(tls)
          .sslParameters(parameters)
          .authenticator(authenticator)
          .version(HttpClient.Version.HTTP_1_1)
          .executor(executor)
          .build()
      )(_.shutdownNow())
      val view = new AcquisitionHttpClient(delegate = delegate, acquisitionTimeout = 200.millis)
      assertEquals(view.connectTimeout(), Optional.of(java.time.Duration.ofMillis(200)))
      assertEquals(delegate.connectTimeout(), Optional.of(java.time.Duration.ofSeconds(5)))
      assertEquals(view.cookieHandler(), Optional.of[CookieHandler](cookies))
      assertEquals(view.followRedirects(), HttpClient.Redirect.ALWAYS)
      assertEquals(view.proxy(), Optional.of[ProxySelector](proxy))
      assert(view.sslContext() eq tls)
      assertEquals(view.sslParameters().getProtocols.toVector, Vector("TLSv1.3"))
      assertEquals(view.sslParameters().getCipherSuites.toVector, delegate.sslParameters().getCipherSuites.toVector)
      assertEquals(view.authenticator(), Optional.of[Authenticator](authenticator))
      assertEquals(view.version(), HttpClient.Version.HTTP_1_1)
      assertEquals(view.executor(), Optional.of[Executor](executor))

  private def withHttpPeer(path: String)(send: (AcquisitionHttpClient, HttpRequest) => HttpResponse[String]): Unit =
    ox.resourceScope:
      val delegate =
        ox.useInScope(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build())(_.shutdownNow())
      val view = new AcquisitionHttpClient(delegate = delegate, acquisitionTimeout = 200.millis)
      def serve(socket: java.net.Socket): Unit =
        val headers = LocalWebSocketPeer.readHeaders(socket = socket)
        assertEquals(headers.takeWhile(_ != '\r'), s"GET /$path HTTP/1.1")
        assert(headers.toLowerCase.contains("x-delegation: retained\r\n"))
        socket.getOutputStream.write(
          "HTTP/1.1 200 OK\r\nContent-Length: 8\r\nConnection: close\r\n\r\nretained".getBytes(UTF_8)
        )
        socket.getOutputStream.flush()
      val response = LocalWebSocketPeer.run(server = serve): uri =>
        val request = HttpRequest.newBuilder(URI.create(uri.replace("ws://", "http://") + s"/$path"))
          .header("X-Delegation", "retained")
          .build()
        send(view, request)
      assertEquals(response.statusCode(), 200)
      assertEquals(response.body(), "retained")

  test("synchronous send delegates request and body handling to the owned JDK client"):
    withHttpPeer(path = "sync")((view, request) => view.send(request, HttpResponse.BodyHandlers.ofString()))

  test("asynchronous send delegates request and completion to the owned JDK client"):
    withHttpPeer(path = "async")((view, request) =>
      view.sendAsync(request, HttpResponse.BodyHandlers.ofString()).get(2, TimeUnit.SECONDS)
    )

  test("push-aware asynchronous send preserves the normal HTTP response"):
    val pushHandler: HttpResponse.PushPromiseHandler[String] = (_, _, _) =>
      fail("HTTP/1.1 peer must not deliver a push promise")
    withHttpPeer(path = "push")((view, request) =>
      view.sendAsync(request, HttpResponse.BodyHandlers.ofString(), pushHandler).get(2, TimeUnit.SECONDS)
    )
