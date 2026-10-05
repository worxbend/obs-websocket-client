package com.worxbend.obs.websocket.client.transport.pekko

import com.worxbend.obs.websocket.client.transport.{AbstractObsTransport, TransportFrame}
import com.worxbend.obs.websocket.client.ObsError
import java.io.IOException
import org.apache.pekko.http.scaladsl.model.ws.PeerClosedConnectionException
import ox.either.catching
import scala.concurrent.Future
import scala.concurrent.duration.*
import sttp.ws.{WebSocket, WebSocketClosed, WebSocketFrame}

/** Pekko adapter over the shared transport loop; keeps only the future runner invocation, frame mapping, and error
  * boundary. Each backend future is driven through [[PekkoRunner]], so every blocking wait is interruptible.
  *
  * Two Pekko-specific fidelity notes: pekko-http answers Ping frames at the protocol layer and never delivers control
  * frames to the user flow, so the Ping/Pong paths exist for interface parity but cannot fire with the real backend;
  * and a peer close with a code other than 1000/1001 fails the stream with `PeerClosedConnectionException`, which
  * carries the peer's code, while a 1000/1001 close completes the flow and surfaces with the synthetic code 1000. sttp
  * also aggregates streamed Pekko messages before delivery, so wire fragmentation is reassembled upstream of this
  * transport.
  */
final private[pekko] class PekkoTransport(
  socket:          WebSocket[Future],
  maxMessageBytes: Int,
  shutdownTimeout: FiniteDuration,
  abortConnection: () => Unit,
  writeTimeout:    FiniteDuration = PekkoOptions.DefaultWriteTimeout,
  readIdleTimeout: Option[FiniteDuration] = None,
) extends AbstractObsTransport(
      maxMessageBytes = maxMessageBytes,
      shutdownTimeout = shutdownTimeout,
      abortConnection = abortConnection,
      writeTimeout    = writeTimeout,
      readIdleTimeout = readIdleTimeout,
    ):
  override protected def receiveFrame(): Either[ObsError, TransportFrame] =
    socketBoundary(operation = PekkoRunner.await(future = socket.receive())).map(toFrame)

  // The backend serializes sends, including against control frames.
  override protected def sendTextFrame(text: String): Either[ObsError, Unit] =
    socketBoundary(operation = PekkoRunner.await(future = socket.sendText(text)))

  override protected def sendPongFrame(payload: Array[Byte]): Either[ObsError, Unit] =
    socketBoundary(operation = PekkoRunner.await(future = socket.send(WebSocketFrame.Pong(payload))))

  override protected def sendCloseFrame(): Either[ObsError, Unit] =
    socketBoundary(operation = PekkoRunner.await(future = socket.close()))

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
      .catching[PeerClosedConnectionException]
      .left
      .map(error => ObsError.Transport(message = "WebSocket closed", closeCode = Some(error.closeCode)))
      .catching[IOException]
      .left
      .map(_ => ObsError.Transport(message = "WebSocket I/O failed"))
      .flatten
      .flatten
