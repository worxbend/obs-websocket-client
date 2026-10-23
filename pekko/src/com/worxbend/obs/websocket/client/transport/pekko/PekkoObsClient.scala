package com.worxbend.obs.websocket.client.transport.pekko

import com.worxbend.obs.websocket.client.{ObsClient, ObsConfig, ObsError, ObsSession}
import org.apache.pekko.actor.ActorSystem

import ox.either.catching
import ox.{resourceScope, timeoutOption, uninterruptible, useInScope, ResourceScope}
import scala.concurrent.Future
import scala.concurrent.duration.*
import sttp.capabilities.pekko.PekkoStreams

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
  /** Create and terminate a private actor system and Pekko backend together with the OBS connection. Abortion fails
    * this connection's byte transport, promptly unblocking reads/writes without relying on connection pools or a
    * peer Close reply. The finite acquisition deadline does not become live TCP idle policy. Scope exit waits
    * for `whenTerminated`, bounded at 5 seconds, before returning; after expiry termination continues in the
    * background.
    */
  def connect[A](
    config:  ObsConfig,
    options: PekkoOptions = PekkoOptions(),
  )(use: ObsSession => A): Either[ObsError, A] =
    config.validate.flatMap: valid =>
      options.validate.flatMap: validOptions =>
        resourceScope:
          val system = useInScope(ActorSystem(name = newSystemName()))(shutdownSystem(_))
          withNativeSystem(system = system, config = valid, options = validOptions)(use = use)

  /** Native factory seam also works with a caller-owned system: only the per-connection transport is aborted. */
  private[pekko] def withNativeSystem[A](system: ActorSystem, config: ObsConfig, options: PekkoOptions)(
    use: ObsSession => A
  ): Either[ObsError, A] =
    resourceScope:
      for
        valid            <- config.validate
        transportOptions <- options.validate
        uri              <- websocketUri(config = valid)
        connection        = useInScope(PekkoConnectionFactory.create(system, valid.connectionTimeout))(_.abort())
        transport        <- open(
                       backend         = connection.backend,
                       uri             = uri,
                       config          = valid,
                       abortConnection = connection.abort,
                       options         = transportOptions,
                       ownership       = AcquisitionOwnership.NativeConnection,
                       awaitClosed     = connection.awaitClosed,
                     )
        result <- ObsClient.withTransport(transport = transport, config = valid)(use = use)
      yield result

  /** The caller retains ownership of the injected backend and its actor system, including on failure; the library
    * never terminates them. Requests use an infinite read timeout so acquisition does not impose live TCP idle policy.
    * The backend must independently enforce a finite connection AND upgrade deadline: sttp's Pekko backend alone
    * cannot enforce a finite upgrade deadline without imposing idle policy. Use a custom native client/factory to
    * supply that guarantee. This entrypoint shields acquisition until that backend deadline, so cancellation may
    * exceed `connectionTimeout`; late sockets are registered before cancellation is unmasked.
    * `abortConnection` must promptly and idempotently
    * force-close this connection, without closing a shared backend, and unblock any pending read/write. It runs on
    * write timeout/interruption and after the bounded Close attempt, including late acquisition and callback failure.
    * Repeated invocations must be harmless. Library does not wait for native stream termination on a caller backend:
    * `abortConnection` runs right after the Close frame is enqueued, so it must not discard queued outbound frames
    * (or must delay until the stream completes).
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
                       ownership       = AcquisitionOwnership.CallerBackend,
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
    ownership:       AcquisitionOwnership,
    awaitClosed:     () => Unit = () => (),
  )(using ResourceScope): Either[ObsError, PekkoTransport] =
    timeoutOption(config.connectionTimeout):
      def acquire(): Either[ObsError, PekkoTransport] =
        PekkoRunner
          .await(future =
            basicRequest
              .get(uri)
              .headers(options.headers.entries.map((name, value) => Header(name, value))*)
              .followRedirects(false)
              .readTimeout(Duration.Inf)
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
                awaitClosed     = awaitClosed,
              )
            )(
              _.close()
            )
      ownership match
        case AcquisitionOwnership.CallerBackend    => uninterruptible(acquire())
        case AcquisitionOwnership.NativeConnection =>
          try acquire()
          catch
            case interrupted: InterruptedException =>
              // Destroy before timeoutOption joins its worker. The switch also kills any late materialization.
              abortConnection()
              throw interrupted
    .getOrElse(Left(ObsError.Timeout(operation = "connection")))

  private enum AcquisitionOwnership:
    case NativeConnection, CallerBackend

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
