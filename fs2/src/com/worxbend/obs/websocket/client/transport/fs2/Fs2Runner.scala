package com.worxbend.obs.websocket.client.transport.fs2

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import java.util.concurrent.{ExecutionException, LinkedBlockingQueue}
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

/** Runs backend effects on the shared global cats-effect runtime while presenting the blocking `ObsTransport` seam.
  * cats-effect's cancelable future publishes success, error, and independent cancellation, including completion of
  * cancellation finalizers. The calling (virtual) thread parks on an interruptible one-shot queue; waiter interruption
  * requests native fiber cancellation without waiting for foreign finalizers and propagates `InterruptedException`.
  * Ordinary failures travel inside `attempt` to preserve their identity (even a caller-supplied `ExecutionException`).
  * Fatal runtime failures bypass `attempt`; only that outer future failure channel needs Promise fatal unboxing.
  * A foreign effect interrupted mid-flight completes on cats-effect worker threads that Ox scope joins cannot await,
  * mirroring the ZIO module's teardown characteristics.
  */
private[fs2] object Fs2Runner:
  given IORuntime = IORuntime.global

  def await[A](effect: IO[A]): A =
    val (future, cancel) = effect.attempt.unsafeToFutureCancelable()
    val completion       = new LinkedBlockingQueue[Try[Either[Throwable, A]]]()
    future.onComplete(result => completion.put(result))(using ExecutionContext.parasitic)
    val outcome =
      try completion.take()
      catch
        case interrupted: InterruptedException =>
          val _ = cancel()
          throw interrupted
    completed(outcome)

  private[fs2] def completed[A](outcome: Try[Either[Throwable, A]]): A = outcome match
    case Success(Right(value)) => value
    case Success(Left(error))  => throw error
    case Failure(execution: ExecutionException) if execution.getCause != null && !NonFatal(execution.getCause) =>
      throw execution.getCause
    case Failure(error) => throw error
