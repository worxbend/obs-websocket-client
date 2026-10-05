package com.worxbend.obs.websocket.client.transport.zio

import _root_.zio.{Runtime, Task, Unsafe}
import java.util.concurrent.LinkedBlockingQueue
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

/** Runs backend effects on the shared default ZIO runtime while presenting the blocking `ObsTransport` seam. Each
  * effect runs to a `CancelableFuture`; the calling (virtual) thread parks on a one-shot queue fed by the completion
  * callback, so a blocked wait is fully interruptible: interruption cancels the underlying ZIO fiber and the
  * `InterruptedException` propagates with no status loss. Ox `fork`/`timeoutOption` supervision therefore composes with
  * every transport operation exactly as it does for the sync backends. `scala.concurrent.Promise` boxes fatal
  * throwables — including `InterruptedException` — into `ExecutionException`, so completions are unboxed to keep
  * interruption semantics intact.
  */
private[zio] object ZioRunner:
  private val runtime: Runtime[Any] = Runtime.default

  def await[A](effect: Task[A]): A =
    Unsafe.unsafe { implicit unsafe =>
      val future     = runtime.unsafe.runToFuture(effect)
      val completion = new LinkedBlockingQueue[Try[A]]()
      val _          = future.onComplete(result => completion.put(result))(using ExecutionContext.parasitic)
      val outcome    =
        try completion.take()
        catch
          case interrupted: InterruptedException =>
            val _ = future.cancel()
            throw interrupted
      outcome match
        case Success(value) => value
        case Failure(error) =>
          throw error match
            case execution: java.util.concurrent.ExecutionException if execution.getCause ne null =>
              execution.getCause
            case other => other
    }
