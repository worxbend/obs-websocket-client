package com.worxbend.obs.websocket.client.transport.pekko

import com.worxbend.obs.websocket.client.transport.MessageAssembler
import com.worxbend.obs.websocket.client.util.Utf8
import com.worxbend.obs.websocket.client.{ObsError, ObsTransport}
import java.io.IOException
import org.apache.pekko.http.scaladsl.model.ws.PeerClosedConnectionException
import ox.either.catching
import ox.{fork, supervised, timeoutOption}
import scala.annotation.tailrec
import scala.concurrent.Future
import scala.concurrent.duration.*
import sttp.ws.{WebSocket, WebSocketClosed, WebSocketFrame}

/** One reader aggregates bounded JSON text; the session serializes application writes. Each backend future is driven
  * through [[PekkoRunner]], so every blocking wait is interruptible.
  *
  * Two Pekko-specific fidelity notes: pekko-http answers Ping frames at the protocol layer and never delivers control
  * frames to the user flow, so the Ping/Pong branches below exist for interface parity but cannot fire with the real
  * backend; and a peer close with a code other than 1000/1001 fails the stream with `PeerClosedConnectionException`,
  * which carries the peer's code, while a 1000/1001 close completes the flow and surfaces with the synthetic code
  * 1000. sttp also aggregates streamed Pekko messages before delivery, so wire fragmentation is reassembled upstream
  * of this transport.
  */
final private[pekko] class PekkoTransport(
  socket:          WebSocket[Future],
  maxMessageBytes: Int,
  shutdownTimeout: FiniteDuration,
  abortConnection: () => Unit,
  writeTimeout:    FiniteDuration = PekkoOptions.DefaultWriteTimeout,
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
        PekkoRunner.await(future = socket.sendText(text))
      )

  override def close(): Unit =
    try
      try
        val _ = boundedWrite(deadline = shutdownTimeout, stage = "shutdown")(operation =
          PekkoRunner.await(future = socket.close())
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
    socketBoundary(operation = PekkoRunner.await(future = socket.receive())) match
      case Left(error)                                           => Left(error)
      case Right(WebSocketFrame.Text(payload, finalFragment, _)) =>
        assembler.appendFragment(payload = payload, finalFragment = finalFragment) match
          case Left(error)          => Left(error)
          case Right(Some(message)) => Right(message)
          case Right(None)          => readMessage(assembler = assembler)
      case Right(WebSocketFrame.Ping(payload)) =>
        boundedWrite(deadline = writeTimeout, stage = "write")(operation =
          PekkoRunner.await(future = socket.send(WebSocketFrame.Pong(payload)))
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
      .catching[PeerClosedConnectionException]
      .left
      .map(error => ObsError.Transport(message = "WebSocket closed", closeCode = Some(error.closeCode)))
      .catching[IOException]
      .left
      .map(_ => ObsError.Transport(message = "WebSocket I/O failed"))
      .flatten
      .flatten
