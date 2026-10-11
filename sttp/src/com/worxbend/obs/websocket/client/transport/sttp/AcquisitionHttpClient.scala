package com.worxbend.obs.websocket.client.transport.sttp

import java.net.{Authenticator, CookieHandler, ProxySelector}
import java.net.http.{HttpClient, HttpRequest, HttpResponse, WebSocket}
import java.util.Optional
import java.util.concurrent.{CompletableFuture, Executor}
import javax.net.ssl.{SSLContext, SSLParameters}
import scala.concurrent.duration.FiniteDuration

/** A non-owning view of the already-constructed client. sttp 4's JDK WebSocket path ignores request readTimeout
  * and copies HttpClient.connectTimeout into its native WebSocket builder instead. Override that value only, so the
  * upgrade gets the remaining operation budget without constructing another runtime or changing proxy/TLS options.
  * Resource ownership and shutdown stay with the underlying client.
  */
final private[sttp] class AcquisitionHttpClient(delegate: HttpClient, acquisitionTimeout: FiniteDuration)
    extends HttpClient:
  override def connectTimeout(): Optional[java.time.Duration] =
    Optional.of(java.time.Duration.ofNanos(acquisitionTimeout.toNanos))
  override def cookieHandler(): Optional[CookieHandler] = delegate.cookieHandler()
  override def followRedirects(): HttpClient.Redirect   = delegate.followRedirects()
  override def proxy(): Optional[ProxySelector]         = delegate.proxy()
  override def sslContext(): SSLContext                 = delegate.sslContext()
  override def sslParameters(): SSLParameters           = delegate.sslParameters()
  override def authenticator(): Optional[Authenticator] = delegate.authenticator()
  override def version(): HttpClient.Version            = delegate.version()
  override def executor(): Optional[Executor]           = delegate.executor()
  override def newWebSocketBuilder(): WebSocket.Builder = delegate.newWebSocketBuilder()
  override def send[T](request: HttpRequest, handler: HttpResponse.BodyHandler[T]): HttpResponse[T] =
    delegate.send(request, handler)
  override def sendAsync[T](
    request: HttpRequest,
    handler: HttpResponse.BodyHandler[T],
  ): CompletableFuture[HttpResponse[T]] = delegate.sendAsync(request, handler)
  override def sendAsync[T](
    request:            HttpRequest,
    handler:            HttpResponse.BodyHandler[T],
    pushPromiseHandler: HttpResponse.PushPromiseHandler[T],
  ): CompletableFuture[HttpResponse[T]] = delegate.sendAsync(request, handler, pushPromiseHandler)
