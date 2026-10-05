package com.worxbend.obs.websocket.client.transport

import com.worxbend.obs.websocket.client.util.Utf8
import com.worxbend.obs.websocket.client.{ObsError, ObsTransport}
import java.util.concurrent.atomic.AtomicBoolean
import ox.{fork, supervised, timeoutOption}
import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** Shared bounded-deadline transport loop used by every backend adapter. Adapters supply only their frame and error
  * mapping: each primitive below is already error-mapped through the adapter's `socketBoundary` equivalent, so this
  * class stays Ox-only with no backend imports.
  *
  * Drift resolved from the former per-backend copies: error mapping applies PER FRAME PRIMITIVE (each receive/send is
  * individually mapped at the adapter), not around the whole aggregation — the effect-bridge backends deliver failures
  * per effect, and the sync backend is equivalent either way. `ExecutionException` unboxing belongs to each adapter's
  * runner/boundary layer, not to this loop.
  *
  * Post-abort guard: `abortConnection` may make the in-flight operation fail with an exception type outside the
  * adapter's mapped set (for example `CancellationException`), and Ox `supervised` would rethrow that fork failure at
  * scope exit even when the body already evaluated to a retryable `Left(Timeout)`. Every abort therefore flips an
  * [[AtomicBoolean]] first, and a fork that then fails with any `NonFatal` reports `ObsError.Transport` instead of
  * failing the scope. On the non-abort path, unmapped exceptions still propagate raw as transport defects.
  *
  * The loop answers Ping frames itself because some backends never deliver control frames to the application;
  * backends whose framework answers at the protocol layer (the JDK client) therefore emit a second, RFC-legal
  * unsolicited Pong.
  *
  * An inbound message that crosses the byte limit aborts the connection before the error is returned: a peer that has
  * already violated the limit must not keep this connection alive while the failure propagates. The limit is enforced
  * on the frames the backend delivers, which for aggregating backends (OkHttp, Pekko) happens after the backend has
  * materialized the payload; the abort bounds the exposure to that one delivery instead of a continued stream.
  *
  * One reader aggregates bounded JSON text with arithmetic UTF-8 byte accounting; the session serializes application
  * writes.
  */
abstract private[client] class AbstractObsTransport(
  maxMessageBytes: Int,
  shutdownTimeout: FiniteDuration,
  abortConnection: () => Unit,
  writeTimeout:    FiniteDuration,
  readIdleTimeout: Option[FiniteDuration],
) extends ObsTransport:

  /** Read the next frame, interruptibly, with backend errors already mapped to `ObsError`. */
  protected def receiveFrame(): Either[ObsError, TransportFrame]

  /** Send a complete text message, with backend errors already mapped. */
  protected def sendTextFrame(text: String): Either[ObsError, Unit]

  /** Send a Pong echoing the Ping payload, with backend errors already mapped. */
  protected def sendPongFrame(payload: Array[Byte]): Either[ObsError, Unit]

  /** Send the normal Close frame, with backend errors already mapped. */
  protected def sendCloseFrame(): Either[ObsError, Unit]

  private val aborted = new AtomicBoolean(false)

  override def receive(): Either[ObsError, String] =
    readIdleTimeout match
      case None           => readMessage(assembler = new MessageAssembler(maxMessageBytes = maxMessageBytes))
      case Some(deadline) => boundedRead(deadline = deadline)

  override def send(text: String): Either[ObsError, Unit] =
    if Utf8.encodedLength(text = text) > maxMessageBytes then
      Left(ObsError.MessageTooLarge(message = "Outgoing message exceeds configured byte limit"))
    else boundedWrite(deadline = writeTimeout, stage = "write")(operation = sendTextFrame(text = text))

  override def close(): Unit =
    try
      try
        val _ = boundedWrite(deadline = shutdownTimeout, stage = "shutdown")(operation = sendCloseFrame())
      catch case _: InterruptedException => Thread.currentThread().interrupt()
    finally abort()

  /** Records the abort before forcing it, so any post-abort failure of an in-flight fork is reported as a mapped
    * `Left` instead of flipping the enclosing scope's already-computed result into a defect.
    */
  private def abort(): Unit =
    aborted.set(true)
    abortConnection()

  /** Only the interruptible join is timed. Destroy the foreign resource before the enclosing scope joins a potentially
    * uninterruptible send. `abortConnection` must promptly unblock all socket operations.
    */
  private def boundedWrite(
    deadline: FiniteDuration,
    stage:    String,
  )(operation: => Either[ObsError, Unit]): Either[ObsError, Unit] =
    supervised:
      val writing = fork(guarded(operation = operation))
      try
        timeoutOption(deadline)(writing.join()).getOrElse:
          abort()
          Left(ObsError.Timeout(operation = stage))
      catch
        case interrupted: InterruptedException =>
          abort()
          throw interrupted

  /** The opt-in read-idle deadline covers the whole receive, including aggregation of every fragment, so a peer that
    * stalls mid-message is detected exactly like a silent one. On expiry the connection is destroyed before joining the
    * reader, matching the write path. Expiry surfaces as `Timeout`, which `ReconnectPolicy` classifies as retryable, so
    * half-open connections engage reconnect instead of stalling event subscriptions forever.
    */
  private def boundedRead(deadline: FiniteDuration): Either[ObsError, String] =
    supervised:
      val reading = fork(guarded(operation = readMessage(assembler = new MessageAssembler(maxMessageBytes))))
      try
        timeoutOption(deadline)(reading.join()).getOrElse:
          abort()
          Left(ObsError.Timeout(operation = "read"))
      catch
        case interrupted: InterruptedException =>
          abort()
          throw interrupted

  private def guarded[A](operation: => Either[ObsError, A]): Either[ObsError, A] =
    try operation
    catch
      case NonFatal(_) if aborted.get() =>
        Left(ObsError.Transport(message = "WebSocket closed", closeCode = None))

  @tailrec
  private def readMessage(assembler: MessageAssembler): Either[ObsError, String] =
    receiveFrame() match
      case Left(error)                                        => Left(error)
      case Right(TransportFrame.Text(payload, finalFragment)) =>
        assembler.appendFragment(payload = payload, finalFragment = finalFragment) match
          case Left(error) =>
            // The peer already violated the configured byte limit; destroy the connection so the loop cannot
            // drain further frames from it while the failure propagates.
            abort()
            Left(error)
          case Right(Some(message)) => Right(message)
          case Right(None)          => readMessage(assembler = assembler)
      case Right(TransportFrame.Ping(payload)) =>
        boundedWrite(deadline = writeTimeout, stage = "write")(operation = sendPongFrame(payload = payload)) match
          case Left(error) => Left(error)
          case Right(_)    => readMessage(assembler = assembler)
      case Right(TransportFrame.Pong)              => readMessage(assembler = assembler)
      case Right(TransportFrame.Close(statusCode)) =>
        Left(ObsError.Transport(message = "WebSocket closed", closeCode = Some(statusCode)))
      case Right(TransportFrame.Binary) =>
        Left(ObsError.UnsupportedMessage(message = "Binary messages are unsupported; use OBS JSON encoding"))
