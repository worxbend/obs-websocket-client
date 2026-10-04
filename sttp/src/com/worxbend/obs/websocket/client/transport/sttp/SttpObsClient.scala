package com.worxbend.obs.websocket.client.transport.sttp

import com.worxbend.obs.websocket.client.{ObsClient, ObsConfig, ObsError, ObsSession}
import _root_.sttp.client4.{SttpClientException, WebSocketSyncBackend, basicRequest}
import _root_.sttp.client4.httpclient.HttpClientSyncBackend
import _root_.sttp.client4.ws.sync.asWebSocketUnsafe
import _root_.sttp.model.{Header, Uri}
import ox.{ResourceScope, resourceScope, timeoutOption, uninterruptible, useInScope}
import ox.either.catching

/** sttp entrypoints own each connection for precisely the callback's lifetime. */
object SttpObsClient:
  /** Create and close a private backend together with the OBS connection. */
  def connect[A](
      config: ObsConfig,
      options: SttpOptions = SttpOptions(),
      clientOptions: JdkClientOptions = JdkClientOptions()
  )(use: ObsSession => A): Either[ObsError, A] =
    config.validate.flatMap: valid =>
      options.validate.flatMap: transportOptions =>
        resourceScope:
          val client = useInScope(clientOptions.build(valid.connectionTimeout))(_.shutdownNow())
          withBackend(HttpClientSyncBackend.usingClient(client), valid, () => client.shutdownNow(), transportOptions)(
            use
          )

  /** The caller retains ownership of the injected backend, including on failure. The backend must enforce its own
    * finite connection/upgrade timeout. Acquisition is shielded because JDK sttp may otherwise leave a late upgrade
    * future alive. `abortConnection` must promptly and idempotently force-close this connection, without closing a
    * shared backend, and unblock any pending read/write. It runs on write timeout/interruption and after the bounded
    * Close attempt, including late acquisition and callback failure. Repeated invocations must be harmless.
    */
  def withBackend[A](
      backend: WebSocketSyncBackend,
      config: ObsConfig,
      abortConnection: () => Unit,
      options: SttpOptions = SttpOptions()
  )(
      use: ObsSession => A
  ): Either[ObsError, A] =
    resourceScope:
      for
        valid <- config.validate
        transportOptions <- options.validate
        uri <- websocketUri(valid)
        transport <- open(backend, uri, valid, abortConnection, transportOptions)
        result <- ObsClient.withTransport(transport, valid)(use)
      yield result

  /** java.net.URI and sttp tokenize URIs with different grammars, so a validated URI is parsed defensively and any
    * residual failure is reported as configuration rather than as a retryable transport fault.
    */
  private[sttp] def websocketUri(config: ObsConfig): Either[ObsError, Uri] =
    Uri.parse(config.uri).left.map(_ => ObsError.InvalidConfiguration("Invalid WebSocket URI"))

  private def open(
      backend: WebSocketSyncBackend,
      uri: Uri,
      config: ObsConfig,
      abortConnection: () => Unit,
      options: SttpOptions
  )(using ResourceScope): Either[ObsError, SttpTransport] =
    timeoutOption(config.connectionTimeout):
      // JDK sttp does not cancel buildAsync when its waiter is interrupted. Shield
      // acquisition until the backend deadline, and register cleanup before unmasking.
      uninterruptible:
        basicRequest
          .get(uri)
          .headers(options.headers.entries.map((name, value) => Header(name, value))*)
          .followRedirects(false)
          .readTimeout(config.connectionTimeout)
          .response(asWebSocketUnsafe)
          .send(backend)
          .body
          .left
          .map(_ => ObsError.Transport("WebSocket upgrade rejected"))
          .catching[SttpClientException]
          .left
          .map(connectionError)
          .flatten
          .map: socket =>
            // Registration happens inside acquisition, before the timeout can discard
            // its result. The outer resource scope outlives both acquisition and use.
            useInScope(
              new SttpTransport(
                socket,
                config.maxMessageBytes,
                config.shutdownTimeout,
                abortConnection,
                options.writeTimeout
              )
            )(
              _.close()
            )
    .getOrElse(Left(ObsError.Timeout("connection")))

  private def connectionError(error: SttpClientException): ObsError =
    val rejected = Iterator
      .iterate(Option(error: Throwable))(_.flatMap(e => Option(e.getCause)))
      .take(16)
      .takeWhile(_.nonEmpty)
      .flatten
      .exists(_.isInstanceOf[java.net.http.WebSocketHandshakeException])
    ObsError.Transport(if rejected then "WebSocket upgrade rejected" else "WebSocket connection failed")
