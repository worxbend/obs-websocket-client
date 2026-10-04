package com.worxbend.obs.websocket.client.transport.sttp

import java.net.ProxySelector
import java.net.http.HttpClient
import javax.net.ssl.SSLContext
import scala.concurrent.duration.FiniteDuration

/** Options for the private JDK client owned by `connect`; backend injection remains available for other backends. TLS
  * hostname verification stays enabled. Custom trust/key material belongs in the supplied SSL context.
  */
final case class JdkClientOptions(
    proxy: Option[ProxySelector] = None,
    sslContext: Option[SSLContext] = None
):
  private[sttp] def build(connectionTimeout: FiniteDuration): HttpClient =
    val builder = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofNanos(connectionTimeout.toNanos))
    proxy.foreach(value => builder.proxy(value))
    sslContext.foreach(value => builder.sslContext(value))
    builder.build()

  override def toString: String = "JdkClientOptions(<redacted>)"
