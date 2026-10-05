package com.worxbend.obs.websocket.client.transport.zio

import com.worxbend.obs.websocket.client.transport.{AbstractObsTransport, TransportFrame}
import com.worxbend.obs.websocket.client.ObsError
import _root_.zio.Task
import java.io.IOException
import ox.either.catching
import scala.concurrent.duration.*
import sttp.ws.{WebSocket, WebSocketClosed, WebSocketFrame}

/** ZIO adapter over the shared transport loop; keeps only the effect runner invocation, frame mapping, and error
  * boundary. Each backend effect is driven through [[ZioRunner]], so every blocking wait is interruptible and cancels
  * the underlying ZIO fiber.
  *
  * The ZIO backend delivers decoded strings over the same JDK WebSocket as the sync backend, so original UTF-8 bytes
  * are unavailable here. Strict rejection of malformed wire UTF-8 therefore depends on the backend's decoding, exactly
  * as documented for the sttp sync transport.
  */
final private[zio] class ZioTransport(
  socket:          WebSocket[Task],
  maxMessageBytes: Int,
  shutdownTimeout: FiniteDuration,
  abortConnection: () => Unit,
  writeTimeout:    FiniteDuration = ZioOptions.DefaultWriteTimeout,
  readIdleTimeout: Option[FiniteDuration] = None,
) extends AbstractObsTransport(
      maxMessageBytes = maxMessageBytes,
      shutdownTimeout = shutdownTimeout,
      abortConnection = abortConnection,
      writeTimeout    = writeTimeout,
      readIdleTimeout = readIdleTimeout,
    ):
  override protected def receiveFrame(): Either[ObsError, TransportFrame] =
    socketBoundary(operation = ZioRunner.await(effect = socket.receive())).map(toFrame)

  // The backend serializes sends, including against control frames.
  override protected def sendTextFrame(text: String): Either[ObsError, Unit] =
    socketBoundary(operation = ZioRunner.await(effect = socket.sendText(text)))

  override protected def sendPongFrame(payload: Array[Byte]): Either[ObsError, Unit] =
    socketBoundary(operation = ZioRunner.await(effect = socket.send(WebSocketFrame.Pong(payload))))

  override protected def sendCloseFrame(): Either[ObsError, Unit] =
    socketBoundary(operation = ZioRunner.await(effect = socket.close()))

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
