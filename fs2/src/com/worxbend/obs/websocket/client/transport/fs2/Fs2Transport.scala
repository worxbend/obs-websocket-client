package com.worxbend.obs.websocket.client.transport.fs2

import cats.effect.IO
import com.worxbend.obs.websocket.client.transport.MessageAssembler
import com.worxbend.obs.websocket.client.util.Utf8
import com.worxbend.obs.websocket.client.{ObsError, ObsTransport}
import java.io.IOException
import ox.either.catching
import ox.{fork, supervised, timeoutOption}
import scala.annotation.tailrec
import scala.concurrent.duration.*
import sttp.ws.{WebSocket, WebSocketClosed, WebSocketFrame}

/** One reader aggregates bounded JSON text; the session serializes application writes. Each backend effect is driven
  * through [[Fs2Runner]], so every blocking wait is interruptible and cancels the underlying cats-effect fiber.
  *
  * The fs2 backend delivers decoded strings over the same JDK WebSocket as the sync backend, so original UTF-8 bytes
  * are unavailable here. Strict rejection of malformed wire UTF-8 therefore depends on the backend's decoding, exactly
  * as documented for the sttp sync transport.
  */
final private[fs2] class Fs2Transport(
  socket:          WebSocket[IO],
  maxMessageBytes: Int,
  shutdownTimeout: FiniteDuration,
  abortConnection: () => Unit,
  writeTimeout:    FiniteDuration = Fs2Options.DefaultWriteTimeout,
  readIdleTimeout: Option[FiniteDuration] = None,
) extends ObsTransport:
  override def receive(): Either[ObsError, String] =
    readIdleTimeout match
      case None           => readMessage(assembler = new MessageAssembler(maxMessageBytes = maxMessageBytes))
      case Some(deadline) => boundedRead(deadline = deadline)

  override def send(text: String): Either[ObsError, Unit] =
    // The backend serializes sends, including against control frames.
    if Utf8.encodedLength(text = text) > maxMessageBytes then
      Left(ObsError.MessageTooLarge(message = "Outgoing message exceeds configured byte limit"))
    else
      boundedWrite(deadline = writeTimeout, stage = "write")(operation =
        Fs2Runner.await(effect = socket.sendText(text))
      )

  override def close(): Unit =
    try
      try
        val _ = boundedWrite(deadline = shutdownTimeout, stage = "shutdown")(operation =
          Fs2Runner.await(effect = socket.close())
        )
      catch case _: InterruptedException => Thread.currentThread().interrupt()
    finally abortConnection()

  /** Only the interruptible join is timed. Destroy the foreign resource before the enclosing scope joins a potentially
    * uninterruptible send. `abortConnection` must promptly unblock all socket operations.
    */
  private def boundedWrite(deadline: FiniteDuration, stage: String)(operation: => Unit): Either[ObsError, Unit] =
    supervised:
      val writing = fork(socketBoundary(operation = operation))
      try
        timeoutOption(deadline)(writing.join()).getOrElse:
          abortConnection()
          Left(ObsError.Timeout(operation = stage))
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
      val reading = fork(readMessage(assembler = new MessageAssembler(maxMessageBytes = maxMessageBytes)))
      try
        timeoutOption(deadline)(reading.join()).getOrElse:
          abortConnection()
          Left(ObsError.Timeout(operation = "read"))
      catch
        case interrupted: InterruptedException =>
          abortConnection()
          throw interrupted

  @tailrec
  private def readMessage(assembler: MessageAssembler): Either[ObsError, String] =
    socketBoundary(operation = Fs2Runner.await(effect = socket.receive())) match
      case Left(error)                                           => Left(error)
      case Right(WebSocketFrame.Text(payload, finalFragment, _)) =>
        assembler.appendFragment(payload = payload, finalFragment = finalFragment) match
          case Left(error)          => Left(error)
          case Right(Some(message)) => Right(message)
          case Right(None)          => readMessage(assembler = assembler)
      case Right(WebSocketFrame.Ping(payload)) =>
        boundedWrite(deadline = writeTimeout, stage = "write")(operation =
          Fs2Runner.await(effect = socket.send(WebSocketFrame.Pong(payload)))
        ) match
          case Left(error) => Left(error)
          case Right(_)    => readMessage(assembler = assembler)
      case Right(_: WebSocketFrame.Pong)      => readMessage(assembler = assembler)
      case Right(close: WebSocketFrame.Close) =>
        Left(ObsError.Transport(message = "WebSocket closed", closeCode = Some(close.statusCode)))
      case Right(_: WebSocketFrame.Binary) =>
        Left(ObsError.UnsupportedMessage(message = "Binary messages are unsupported; use OBS JSON encoding"))

  private def socketBoundary[A](operation: => A): Either[ObsError, A] =
    operation
      .catching[WebSocketClosed]
      .left
      .map(error => ObsError.Transport(message = "WebSocket closed", closeCode = error.frame.map(_.statusCode)))
      .catching[IOException]
      .left
      .map(_ => ObsError.Transport(message = "WebSocket I/O failed"))
      .flatten
