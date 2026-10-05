package com.worxbend.obs.websocket.client.transport.fs2

import cats.effect.IO
import cats.effect.std.Dispatcher
import com.worxbend.obs.websocket.client.{ObsClient, ObsConfig, ObsError, ObsSession}
import Fs2Runner.given
import ox.either.catching
import ox.{resourceScope, timeoutOption, uninterruptible, useInScope, ResourceScope}
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.httpclient.fs2.HttpClientFs2Backend
import sttp.client4.ws.async.asWebSocketUnsafe
import sttp.client4.{basicRequest, SttpClientException, WebSocketStreamBackend}
import sttp.model.{Header, Uri}

/** fs2 entrypoints own each connection for precisely the callback's lifetime, mirroring `SttpObsClient`. The blocking
  * direct-style session API is presented unchanged; the cats-effect runtime and the dispatcher stay internal
  * implementation details. Callers needing a custom JDK client (proxy, TLS) supply their own backend through
  * [[withBackend]].
  *
  * sttp's async HTTP-client backend recovers a failed upgrade handshake into a delivered response, so an HTTP upgrade
  * rejection always surfaces as "WebSocket upgrade rejected" through the response-body path; a thrown backend exception
  * means the connection itself failed and maps to "WebSocket connection failed".
  */
object Fs2ObsClient:
  /** Create and close a private JDK client, dispatcher, and fs2 backend together with the OBS connection. */
  def connect[A](
    config:  ObsConfig,
    options: Fs2Options = Fs2Options(),
  )(use: ObsSession => A): Either[ObsError, A] =
    config.validate.flatMap: valid =>
      resourceScope:
        val client = useInScope(
          java.net.http.HttpClient
            .newBuilder()
            .connectTimeout(java.time.Duration.ofNanos(valid.connectionTimeout.toNanos))
            .build()
        )(_.shutdownNow())
        val (dispatcher, releaseDispatcher) = Dispatcher.parallel[IO].allocated.unsafeRunSync()
        val backend                         = useInScope(
          HttpClientFs2Backend.usingClient[IO](client = client, dispatcher = dispatcher)
        )(_ => releaseDispatcher.unsafeRunSync())
        withBackend(
          backend = backend,
          config  = valid,
          () => client.shutdownNow(),
          options = options,
        )(
          use = use
        )

  /** The caller retains ownership of the injected backend, including on failure. The backend must enforce its own
    * finite connection/upgrade timeout. Acquisition is shielded because the underlying JDK client may otherwise leave
    * a late upgrade future alive. `abortConnection` must promptly and idempotently force-close this connection,
    * without closing a shared backend, and unblock any pending read/write. It runs on write timeout/interruption and
    * after the bounded Close attempt, including late acquisition and callback failure. Repeated invocations must be
    * harmless.
    */
  def withBackend[A](
    backend:         WebSocketStreamBackend[IO, Fs2Streams[IO]],
    config:          ObsConfig,
    abortConnection: () => Unit,
    options:         Fs2Options = Fs2Options(),
  )(
    use: ObsSession => A
  ): Either[ObsError, A] =
    resourceScope:
      for
        valid            <- config.validate
        transportOptions <- options.validate
        uri              <- websocketUri(config = valid)
        transport        <- open(
                       backend         = backend,
                       uri             = uri,
                       config          = valid,
                       abortConnection = abortConnection,
                       options         = transportOptions,
                     )
        result <- ObsClient.withTransport(transport = transport, config = valid)(use = use)
      yield result

  /** java.net.URI and sttp tokenize URIs with different grammars, so a validated URI is parsed defensively and any
    * residual failure is reported as configuration rather than as a retryable transport fault.
    */
  private[fs2] def websocketUri(config: ObsConfig): Either[ObsError, Uri] =
    Uri.parse(config.uri).left.map(_ => ObsError.InvalidConfiguration(message = "Invalid WebSocket URI"))

  private def open(
    backend:         WebSocketStreamBackend[IO, Fs2Streams[IO]],
    uri:             Uri,
    config:          ObsConfig,
    abortConnection: () => Unit,
    options:         Fs2Options,
  )(using ResourceScope): Either[ObsError, Fs2Transport] =
    timeoutOption(config.connectionTimeout):
      // The JDK client underneath the fs2 backend does not cancel buildAsync when its waiter is interrupted. Shield
      // acquisition until the backend deadline, and register cleanup before unmasking.
      uninterruptible:
        Fs2Runner
          .await(effect =
            basicRequest
              .get(uri)
              .headers(options.headers.entries.map((name, value) => Header(name, value))*)
              .followRedirects(false)
              .readTimeout(config.connectionTimeout)
              .response(asWebSocketUnsafe)
              .send(backend)
              .map(_.body)
          )
          .left
          .map(_ => ObsError.Transport(message = "WebSocket upgrade rejected"))
          .catching[SttpClientException]
          .left
          .map(_ => ObsError.Transport(message = "WebSocket connection failed"))
          .flatten
          .map: socket =>
            // Registration happens inside acquisition, before the timeout can discard
            // its result. The outer resource scope outlives both acquisition and use.
            useInScope(
              new Fs2Transport(
                socket          = socket,
                maxMessageBytes = config.maxMessageBytes,
                shutdownTimeout = config.shutdownTimeout,
                abortConnection = abortConnection,
                writeTimeout    = options.writeTimeout,
                readIdleTimeout = options.readIdleTimeout,
              )
            )(
              _.close()
            )
    .getOrElse(Left(ObsError.Timeout(operation = "connection")))
