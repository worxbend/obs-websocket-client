package com.worxbend.obs.websocket.client.transport.sttp

import com.worxbend.obs.websocket.client.util.Utf8
import com.worxbend.obs.websocket.client.{ObsError, ObsTransport}
import _root_.sttp.client4.ws.SyncWebSocket
import _root_.sttp.ws.{WebSocketClosed, WebSocketFrame}
import java.io.IOException
import java.util.concurrent.ExecutionException
import ox.either.catching
import ox.{fork, supervised, timeoutOption}
import scala.annotation.tailrec
import scala.concurrent.duration.*

/** One reader aggregates bounded JSON text; the session serializes application writes.
  *
  * sttp's text API delivers decoded strings, so original UTF-8 bytes are unavailable here. If a backend replaces
  * invalid bytes with U+FFFD, that replacement can remain valid inside a JSON string and pass protocol decoding. Strict
  * rejection of malformed wire UTF-8 therefore depends on the selected WebSocket backend.
  */
private[sttp] final class SttpTransport(
    socket: SyncWebSocket,
    maxMessageBytes: Int,
    shutdownTimeout: FiniteDuration,
    abortConnection: () => Unit,
    writeTimeout: FiniteDuration = SttpOptions.DefaultWriteTimeout,
    readIdleTimeout: Option[FiniteDuration] = None
) extends ObsTransport:
  override def receive(): Either[ObsError, String] =
    readIdleTimeout match
      case None           => socketBoundary(readMessage(new java.lang.StringBuilder, 0L)).flatten
      case Some(deadline) => boundedRead(deadline)

  override def send(text: String): Either[ObsError, Unit] =
    // SyncWebSocket guarantees thread-safe sends, including serialization against control frames.
    if Utf8.encodedLength(text) > maxMessageBytes then
      Left(ObsError.MessageTooLarge("Outgoing message exceeds configured byte limit"))
    else boundedWrite(writeTimeout, "write")(socket.sendText(text))

  override def close(): Unit =
    try
      try
        val _ = boundedWrite(shutdownTimeout, "shutdown")(socket.close())
      catch case _: InterruptedException => Thread.currentThread().interrupt()
    finally abortConnection()

  /** Only the interruptible join is timed. Destroy the foreign resource before the enclosing scope joins a potentially
    * uninterruptible send. `abortConnection` must promptly unblock all socket operations.
    */
  private def boundedWrite(deadline: FiniteDuration, stage: String)(operation: => Unit): Either[ObsError, Unit] =
    supervised:
      val writing = fork(socketBoundary(operation))
      try
        timeoutOption(deadline)(writing.join()).getOrElse:
          abortConnection()
          Left(ObsError.Timeout(stage))
      catch
        case interrupted: InterruptedException =>
          abortConnection()
          throw interrupted

  /** The opt-in read-idle deadline covers the whole receive, including aggregation of every fragment, so a peer that
    * stalls mid-message is detected exactly like a silent one. On expiry the connection is destroyed before joining the
    * reader, matching the write path. Expiry surfaces as `Timeout`, which `ReconnectPolicy` classifies as retryable, so
    * half-open connections engage reconnect instead of stalling event subscriptions forever.
    */
  private def boundedRead(deadline: FiniteDuration): Either[ObsError, String] =
    supervised:
      val reading = fork(socketBoundary(readMessage(new java.lang.StringBuilder, 0L)).flatten)
      try
        timeoutOption(deadline)(reading.join()).getOrElse:
          abortConnection()
          Left(ObsError.Timeout("read"))
      catch
        case interrupted: InterruptedException =>
          abortConnection()
          throw interrupted

  @tailrec
  private def readMessage(text: java.lang.StringBuilder, bytes: Long): Either[ObsError, String] =
    socket.receive() match
      case WebSocketFrame.Text(payload, finalFragment, _) =>
        // Arithmetic per fragment, so no encoded array is materialized per fragment.
        val total = bytes + Utf8.encodedLength(payload)
        if total > maxMessageBytes then Left(ObsError.MessageTooLarge("Incoming message exceeds configured byte limit"))
        else
          val _ = text.append(payload)
          if finalFragment then Right(text.toString)
          else readMessage(text, total)
      case WebSocketFrame.Ping(payload) =>
        boundedWrite(writeTimeout, "write")(socket.send(WebSocketFrame.Pong(payload))) match
          case Left(error) => Left(error)
          case Right(_)    => readMessage(text, bytes)
      case _: WebSocketFrame.Pong      => readMessage(text, bytes)
      case close: WebSocketFrame.Close =>
        Left(ObsError.Transport("WebSocket closed", Some(close.statusCode)))
      case _: WebSocketFrame.Binary =>
        Left(ObsError.UnsupportedMessage("Binary messages are unsupported; use OBS JSON encoding"))

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
