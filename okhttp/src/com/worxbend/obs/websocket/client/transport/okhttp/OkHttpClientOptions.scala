package com.worxbend.obs.websocket.client.transport.okhttp

import com.worxbend.obs.websocket.client.ObsError
import java.net.Proxy
import okhttp3.OkHttpClient
import scala.concurrent.duration.*

/** Options for the private OkHttp client owned by `connect`; backend injection remains available through
  * `SttpObsClient.withBackend`. The connection deadline always comes from `ObsConfig.connectionTimeout`, matching the
  * JDK backend entrypoint; the optional read/write/call deadlines map onto the OkHttp client's own socket budgets and
  * complement — never replace — the session-level deadlines in `SttpOptions` and `ObsConfig`.
  */
final case class OkHttpClientOptions(
  proxy:        Option[Proxy] = None,
  readTimeout:  Option[FiniteDuration] = None,
  writeTimeout: Option[FiniteDuration] = None,
  callTimeout:  Option[FiniteDuration] = None,
):
  def validate: Either[ObsError, OkHttpClientOptions] =
    if Seq(readTimeout, writeTimeout, callTimeout).exists(_.exists(_ <= Duration.Zero)) then
      Left(ObsError.InvalidConfiguration(message = "OkHttp client deadlines must be positive"))
    else Right(this)

  private[okhttp] def build(connectionTimeout: FiniteDuration): OkHttpClient =
    val builder = new OkHttpClient.Builder().connectTimeout(java.time.Duration.ofNanos(connectionTimeout.toNanos))
    proxy.foreach(value => builder.proxy(value))
    readTimeout.foreach(value => builder.readTimeout(java.time.Duration.ofNanos(value.toNanos)))
    writeTimeout.foreach(value => builder.writeTimeout(java.time.Duration.ofNanos(value.toNanos)))
    callTimeout.foreach(value => builder.callTimeout(java.time.Duration.ofNanos(value.toNanos)))
    builder.build()

  override def toString: String =
    s"OkHttpClientOptions(proxy=<redacted>, readTimeout=$readTimeout, writeTimeout=$writeTimeout, callTimeout=$callTimeout)"
