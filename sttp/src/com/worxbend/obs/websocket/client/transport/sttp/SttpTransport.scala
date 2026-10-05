package com.worxbend.obs.websocket.client.transport.sttp

import com.worxbend.obs.websocket.client.transport.{AbstractObsTransport, TransportFrame}
import com.worxbend.obs.websocket.client.ObsError
import _root_.sttp.client4.ws.SyncWebSocket
import _root_.sttp.ws.{WebSocketClosed, WebSocketFrame}
import java.io.IOException
import java.util.concurrent.ExecutionException
import ox.either.catching
import scala.concurrent.duration.*

/** JDK sync adapter over the shared transport loop; keeps only the sttp frame mapping and the sync error boundary.
  *
  * sttp's text API delivers decoded strings, so original UTF-8 bytes are unavailable here. If a backend replaces
  * invalid bytes with U+FFFD, that replacement can remain valid inside a JSON string and pass protocol decoding. Strict
  * rejection of malformed wire UTF-8 therefore depends on the selected WebSocket backend.
  */
final private[sttp] class SttpTransport(
  socket:          SyncWebSocket,
  maxMessageBytes: Int,
  shutdownTimeout: FiniteDuration,
  abortConnection: () => Unit,
  writeTimeout:    FiniteDuration = SttpOptions.DefaultWriteTimeout,
  readIdleTimeout: Option[FiniteDuration] = None,
) extends AbstractObsTransport(
      maxMessageBytes = maxMessageBytes,
      shutdownTimeout = shutdownTimeout,
      abortConnection = abortConnection,
      writeTimeout    = writeTimeout,
      readIdleTimeout = readIdleTimeout,
    ):
  override protected def receiveFrame(): Either[ObsError, TransportFrame] =
    socketBoundary(operation = socket.receive()).map(toFrame)

  // SyncWebSocket guarantees thread-safe sends, including serialization against control frames.
  override protected def sendTextFrame(text: String): Either[ObsError, Unit] =
    socketBoundary(operation = socket.sendText(text))

  override protected def sendPongFrame(payload: Array[Byte]): Either[ObsError, Unit] =
    socketBoundary(operation = socket.send(WebSocketFrame.Pong(payload)))

  override protected def sendCloseFrame(): Either[ObsError, Unit] =
    socketBoundary(operation = socket.close())

  private def toFrame(frame: WebSocketFrame): TransportFrame = frame match
    case WebSocketFrame.Text(payload, finalFragment, _) => TransportFrame.Text(payload, finalFragment)
    case _: WebSocketFrame.Binary                       => TransportFrame.Binary
    case WebSocketFrame.Ping(payload)                   => TransportFrame.Ping(payload)
    case _: WebSocketFrame.Pong                         => TransportFrame.Pong
    case close: WebSocketFrame.Close                    => TransportFrame.Close(close.statusCode)

  private def socketBoundary[A](operation: => A): Either[ObsError, A] =
    operation
      .catching[WebSocketClosed]
      .left
      .map(error => ObsError.Transport(message = "WebSocket closed", closeCode = error.frame.map(_.statusCode)))
      .catching[IOException]
      .left
      .map(_ => ObsError.Transport(message = "WebSocket I/O failed"))
      .flatten
      .catching[ExecutionException]
      .left
      .map(_ => ObsError.Transport(message = "WebSocket operation failed"))
      .flatten
