package com.worxbend.obs.websocket.client.transport.pekko

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.{ClientTransport, Http}
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}
import org.apache.pekko.http.scaladsl.model.ws.{Message, WebSocketRequest, WebSocketUpgradeResponse}
import org.apache.pekko.http.scaladsl.settings.{ClientConnectionSettings, ConnectionPoolSettings}
import org.apache.pekko.stream.{KillSwitches, Materializer, SharedKillSwitch}
import org.apache.pekko.stream.scaladsl.{Flow, Keep}
import org.apache.pekko.util.ByteString
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import sttp.capabilities.pekko.PekkoStreams
import sttp.client4.{BackendOptions, WebSocketStreamBackend}
import sttp.client4.pekkohttp.{PekkoHttpBackend, PekkoHttpClient}

/** One factory instance per connection, never per shared system/backend. The switch sits at the byte transport,
  * below HTTP/TLS/WebSocket close handling. Failing both directions reaches Pekko TCP's Abort path rather than
  * waiting for the peer's WebSocket Close reply. A shared switch remembers abort even before late materialization.
  */
private[pekko] object PekkoConnectionFactory:
  final case class Connection(
    backend:     WebSocketStreamBackend[Future, PekkoStreams],
    abort:       () => Unit,
    awaitClosed: () => Unit,
  )

  def create(system: ActorSystem, connectionTimeout: FiniteDuration): Connection =
    val kill    = KillSwitches.shared("obs-websocket-connection")
    val closed  = Promise[org.apache.pekko.Done]()
    val http    = new AbortableClient(system = system, kill = kill, closed = closed)
    val backend = PekkoHttpBackend.usingClient(
      actorSystem = system,
      options     = BackendOptions.Default.connectionTimeout(connectionTimeout),
      http        = http,
    )
    def waitForClose(): Unit =
      val _ = PekkoRunner.await(closed.future)
    Connection(
      backend     = backend,
      abort       = () => kill.abort(new java.io.IOException("WebSocket connection aborted")),
      awaitClosed = () => waitForClose(),
    )

  final private class AbortableClient(
    system: ActorSystem,
    kill:   SharedKillSwitch,
    closed: Promise[org.apache.pekko.Done],
  ) extends PekkoHttpClient:
    private val delegate = PekkoHttpClient.default(system, None, None)
    private val http     = Http(system)

    override def singleRequest(request: HttpRequest, settings: ConnectionPoolSettings): Future[HttpResponse] =
      delegate.singleRequest(request, settings)

    override def singleWebsocketRequest[A](
      request:    WebSocketRequest,
      clientFlow: Flow[Message, Message, A],
      settings:   ClientConnectionSettings,
    )(using ec: ExecutionContext, mat: Materializer): Future[(WebSocketUpgradeResponse, A)] =
      // Wrap the resolved transport AFTER sttp applies proxy options, preserving proxy and TLS behavior.
      // This is Pekko 1.4.0 singleWebSocketRequest's materialization with an incoming WebSocket completion watcher.
      // Watching TCP alone would wait for peer FIN even after the WebSocket Close exchange has finished.
      val (upgrade, result) = http
        .webSocketClientFlow(
          request,
          settings =
            settings.withIdleTimeout(Duration.Inf).withTransport(new AbortableTransport(settings.transport, kill)),
        )
        .watchTermination(): (upgrade, done) =>
          val _ = closed.completeWith(done)
          upgrade
        .joinMat(clientFlow)(Keep.both)
        .run()
      upgrade.map(response => (response, result))

  final private class AbortableTransport(
    delegate: ClientTransport,
    kill:     SharedKillSwitch,
  ) extends ClientTransport:
    override def connectTo(host: String, port: Int, settings: ClientConnectionSettings)(
      using system: ActorSystem
    ): Flow[ByteString, ByteString, Future[Http.OutgoingConnection]] =
      Flow.fromGraph(kill.flow[ByteString])
        .viaMat(delegate.connectTo(host, port, settings))(Keep.right)
        .via(kill.flow[ByteString])
