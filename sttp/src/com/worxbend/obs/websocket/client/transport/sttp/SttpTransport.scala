package com.worxbend.obs.websocket.client.transport.sttp

import com.worxbend.obs.websocket.client.{ObsError, ObsTransport}
import _root_.sttp.client4.ws.SyncWebSocket
import _root_.sttp.ws.{WebSocketClosed, WebSocketFrame}
import java.io.IOException
import java.nio.{ByteBuffer, CharBuffer}
import java.nio.charset.{CharsetEncoder, CodingErrorAction}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ExecutionException
import ox.either.catching
import ox.{fork, supervised, timeoutOption}
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
    writeTimeout: FiniteDuration = 10.seconds
) extends ObsTransport:
  private val utf8ScratchBytes = 4096
  override def receive(): Either[ObsError, String] =
    socketBoundary(readMessage()).flatten

  override def send(text: String): Either[ObsError, Unit] =
    // SyncWebSocket guarantees thread-safe sends, including serialization against control frames.
    // Each call owns its encoder so concurrent control/application writes need no uninterruptible monitor.
    if utf8ByteLength(newUtf8Encoder(), ByteBuffer.allocate(utf8ScratchBytes), text) > maxMessageBytes then
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

  private def readMessage(): Either[ObsError, String] =
    val text = new java.lang.StringBuilder
    // Shared across the fragments of one message, so no encoded array is materialized per fragment.
    val encoder = newUtf8Encoder()
    val scratch = ByteBuffer.allocate(utf8ScratchBytes)
    var bytes = 0L
    var result = Option.empty[Either[ObsError, String]]
    while result.isEmpty do
      socket.receive() match
        case WebSocketFrame.Text(payload, finalFragment, _) =>
          bytes += utf8ByteLength(encoder, scratch, payload)
          if bytes > maxMessageBytes then
            result = Some(Left(ObsError.MessageTooLarge("Incoming message exceeds configured byte limit")))
          else
            val _ = text.append(payload)
            if finalFragment then result = Some(Right(text.toString))
        case WebSocketFrame.Ping(payload) =>
          boundedWrite(writeTimeout, "write")(socket.send(WebSocketFrame.Pong(payload))) match
            case Left(error) => result = Some(Left(error))
            case Right(_)    => ()
        case _: WebSocketFrame.Pong      => ()
        case close: WebSocketFrame.Close =>
          result = Some(Left(ObsError.Transport("WebSocket closed", Some(close.statusCode))))
        case _: WebSocketFrame.Binary =>
          result = Some(Left(ObsError.UnsupportedMessage("Binary messages are unsupported; use OBS JSON encoding")))
    result.get

  private def newUtf8Encoder(): CharsetEncoder =
    UTF_8
      .newEncoder()
      .onMalformedInput(CodingErrorAction.REPLACE)
      .onUnmappableCharacter(CodingErrorAction.REPLACE)

  /** Exact UTF-8 length, identical to `String.getBytes(UTF_8).length` down to malformed-input replacement, measured
    * through a reusable encoder and scratch buffer instead of materializing the encoded array.
    */
  private def utf8ByteLength(encoder: CharsetEncoder, scratch: ByteBuffer, value: String): Long =
    encoder.reset()
    val in = CharBuffer.wrap(value)
    var bytes = 0L
    var encoding = true
    while encoding do
      scratch.clear()
      val result = encoder.encode(in, scratch, true)
      bytes += scratch.position()
      encoding = result.isOverflow
    scratch.clear()
    val _ = encoder.flush(scratch)
    bytes + scratch.position()

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
