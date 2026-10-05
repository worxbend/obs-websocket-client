package com.worxbend.obs.websocket.client.transport

import java.net.ProxySelector
import java.net.http.HttpClient
import javax.net.ssl.SSLContext
import scala.concurrent.duration.FiniteDuration

/** Options for the private JDK client owned by `connect` on the JDK-backed adapters (sttp, zio, fs2); backend
  * injection remains available for other backends. TLS hostname verification stays enabled. Custom trust/key material
  * belongs in the supplied SSL context. Rendering redacts the proxy and SSL context identities, which may carry
  * environment-specific or sensitive details.
  */
final case class JdkClientOptions(
  proxy:      Option[ProxySelector] = None,
  sslContext: Option[SSLContext] = None,
):
  private[client] def build(connectionTimeout: FiniteDuration): HttpClient =
    val builder = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofNanos(connectionTimeout.toNanos))
    proxy.foreach(value => builder.proxy(value))
    sslContext.foreach(value => builder.sslContext(value))
    builder.build()

  override def toString: String = "JdkClientOptions(proxy=<redacted>, sslContext=<redacted>)"
