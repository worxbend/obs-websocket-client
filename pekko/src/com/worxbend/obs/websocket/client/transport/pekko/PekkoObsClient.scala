package com.worxbend.obs.websocket.client.transport.pekko

import com.worxbend.obs.websocket.client.{ObsClient, ObsConfig, ObsError, ObsSession}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import ox.either.catching
import ox.{resourceScope, timeoutOption, uninterruptible, useInScope, ResourceScope}
import scala.concurrent.Future
import scala.concurrent.duration.*
import sttp.capabilities.pekko.PekkoStreams
import sttp.client4.pekkohttp.PekkoHttpBackend
import sttp.client4.ws.async.asWebSocketUnsafe
import sttp.client4.{basicRequest, SttpClientException, WebSocketStreamBackend}
import sttp.model.{Header, Uri}

/** Pekko entrypoints own each connection for precisely the callback's lifetime, mirroring `SttpObsClient`. The
  * blocking direct-style session API is presented unchanged; the `ActorSystem` stays an internal implementation
  * detail. Callers needing a custom actor system supply their own backend through [[withBackend]]; the library never
  * terminates a caller-owned system. Unlike the JDK-backed adapters (sttp, zio, fs2), there is no `JdkClientOptions`
  * parameter: the backend is Pekko-native, so proxy/TLS/pool tuning belongs to the caller-supplied backend through
  * [[withBackend]] or to Pekko configuration.
  *
  * sttp's Pekko backend reports a failed upgrade handshake as a delivered response, so an HTTP upgrade rejection
  * always surfaces as "WebSocket upgrade rejected" through the response-body path; a thrown backend exception means
  * the connection itself failed and maps to "WebSocket connection failed".
  */
object PekkoObsClient:
  /** Create and terminate a private actor system and Pekko backend together with the OBS connection. Abortion shuts
    * down the owned system's connection pools, which promptly and idempotently force-closes this connection and
    * unblocks pending reads and writes; repeated invocation is harmless. Scope exit terminates the system and waits
    * for `whenTerminated` before returning.
    */
  def connect[A](
    config:  ObsConfig,
    options: PekkoOptions = PekkoOptions(),
  )(use: ObsSession => A): Either[ObsError, A] =
    config.validate.flatMap: valid =>
      options.validate.flatMap: validOptions =>
        resourceScope:
          val system = useInScope(ActorSystem(name = newSystemName()))(shutdownSystem(_))
          withBackend(
            backend = PekkoHttpBackend.usingActorSystem(actorSystem = system),
            config  = valid,
            () => shutdownConnectionPools(system = system),
            options = validOptions,
          )(
            use = use
          )

  /** The caller retains ownership of the injected backend and its actor system, including on failure; the library
    * never terminates them. The backend must enforce its own finite connection/upgrade timeout. Acquisition is
    * shielded so a late pool response cannot escape registration. `abortConnection` must promptly and idempotently
    * force-close this connection, without closing a shared backend, and unblock any pending read/write. It runs on
    * write timeout/interruption and after the bounded Close attempt, including late acquisition and callback failure.
    * Repeated invocations must be harmless.
    */
  def withBackend[A](
    backend:         WebSocketStreamBackend[Future, PekkoStreams],
    config:          ObsConfig,
    abortConnection: () => Unit,
    options:         PekkoOptions = PekkoOptions(),
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
  private[pekko] def websocketUri(config: ObsConfig): Either[ObsError, Uri] =
    Uri.parse(config.uri).left.map(_ => ObsError.InvalidConfiguration(message = "Invalid WebSocket URI"))

  private def open(
    backend:         WebSocketStreamBackend[Future, PekkoStreams],
    uri:             Uri,
    config:          ObsConfig,
    abortConnection: () => Unit,
    options:         PekkoOptions,
  )(using ResourceScope): Either[ObsError, PekkoTransport] =
    timeoutOption(config.connectionTimeout):
      // The connection pool underneath the Pekko backend does not abandon an in-flight upgrade when a waiter is
      // interrupted. Shield acquisition until the backend deadline, and register cleanup before unmasking.
      uninterruptible:
        PekkoRunner
          .await(future =
            basicRequest
              .get(uri)
              .headers(options.headers.entries.map((name, value) => Header(name, value))*)
              .followRedirects(false)
              .readTimeout(config.connectionTimeout)
              .response(asWebSocketUnsafe)
              .send(backend)
              .map(_.body)(using scala.concurrent.ExecutionContext.parasitic)
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
              new PekkoTransport(
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

  private def shutdownConnectionPools(system: ActorSystem): Unit =
    val _ = Http(system).shutdownAllConnectionPools()

  /** Termination normally completes in milliseconds; the bounded await exists so a wedged actor system cannot pin the
    * closing scope forever. After expiry the scope proceeds and termination continues in the background.
    */
  private[pekko] def shutdownSystem(
    system:  ActorSystem,
    timeout: FiniteDuration = PekkoObsClient.TerminationTimeout,
  ): Unit =
    val _ = system.terminate()
    val _ = timeoutOption(timeout)(PekkoRunner.await(future = system.whenTerminated))

  private[pekko] def newSystemName(): String =
    s"obs-websocket-client-pekko-${SystemIds.incrementAndGet()}"

  private val SystemIds = new java.util.concurrent.atomic.AtomicLong(0)

  /** Bounds the termination wait at scope exit; see [[shutdownSystem]]. */
  private val TerminationTimeout: FiniteDuration = 5.seconds
