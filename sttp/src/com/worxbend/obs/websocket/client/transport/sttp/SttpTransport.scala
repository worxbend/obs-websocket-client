package com.worxbend.obs.websocket.client.transport.sttp

import com.worxbend.obs.websocket.client.{ObsError, ObsTransport}
import _root_.sttp.client4.ws.SyncWebSocket
import _root_.sttp.ws.{WebSocketClosed, WebSocketFrame}
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ExecutionException
import ox.either.catching
import ox.timeoutOption
import scala.concurrent.duration.FiniteDuration

/** One reader aggregates bounded JSON text; the session serializes application writes. */
private[sttp] final class SttpTransport(
    socket: SyncWebSocket,
    maxMessageBytes: Int,
    shutdownTimeout: FiniteDuration,
    abortConnection: () => Unit
) extends ObsTransport:
  override def receive(): Either[ObsError, String] =
    socketBoundary(readMessage()).flatten

  override def send(text: String): Either[ObsError, Unit] =
    if text.getBytes(UTF_8).length > maxMessageBytes then
      Left(ObsError.MessageTooLarge("Outgoing message exceeds configured byte limit"))
    else socketBoundary(socket.sendText(text))

  override def close(): Unit =
    try
      val _ = timeoutOption(shutdownTimeout)(socketBoundary(socket.close()))
    finally abortConnection()

  private def readMessage(): Either[ObsError, String] =
    val text = new java.lang.StringBuilder
    var bytes = 0L
    var result = Option.empty[Either[ObsError, String]]
    while result.isEmpty do
      socket.receive() match
        case WebSocketFrame.Text(payload, finalFragment, _) =>
          bytes += payload.getBytes(UTF_8).length
          if bytes > maxMessageBytes then
            result = Some(Left(ObsError.MessageTooLarge("Incoming message exceeds configured byte limit")))
          else
            val _ = text.append(payload)
            if finalFragment then result = Some(Right(text.toString))
        case WebSocketFrame.Ping(payload) => socket.send(WebSocketFrame.Pong(payload))
        case _: WebSocketFrame.Pong       => ()
        case close: WebSocketFrame.Close  =>
          result = Some(Left(ObsError.Transport("WebSocket closed", Some(close.statusCode))))
        case _: WebSocketFrame.Binary =>
          result = Some(Left(ObsError.UnsupportedMessage("Binary messages are unsupported; use OBS JSON encoding")))
    result.get

  private def socketBoundary[A](operation: => A): Either[ObsError, A] =
    operation
      .catching[WebSocketClosed]
      .left
      .map(error => ObsError.Transport("WebSocket closed", error.frame.map(_.statusCode)))
      .catching[IOException]
      .left
      .map(_ => ObsError.Transport("WebSocket I/O failed"))
      .flatten
      .catching[ExecutionException]
      .left
      .map(_ => ObsError.Transport("WebSocket operation failed"))
      .flatten
