package com.worxbend.obs.websocket.client.transport.pekko

import java.util.concurrent.LinkedBlockingQueue
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/** Blocks on backend futures while presenting the blocking `ObsTransport` seam. The calling (virtual) thread parks on
  * a one-shot queue fed by the completion callback, so a blocked wait is fully interruptible and the
  * `InterruptedException` propagates with no status loss. Pekko futures carry no cancel handle: an abandoned wait is
  * resolved when `abortConnection` tears down the connection pool, which every timeout/interruption path in
  * [[PekkoTransport]] performs before joining. `scala.concurrent.Promise` boxes fatal throwables — including
  * `InterruptedException` — into `ExecutionException`, so completions are unboxed to keep interruption semantics
  * intact.
  */
private[pekko] object PekkoRunner:
  def await[A](future: => Future[A]): A =
    val completion = new LinkedBlockingQueue[Try[A]]()
    val _          = future.onComplete(result => completion.put(result))(using ExecutionContext.parasitic)
    completion.take() match
      case Success(value) => value
      case Failure(error) =>
        throw error match
          case execution: java.util.concurrent.ExecutionException if execution.getCause ne null =>
            execution.getCause
          case other => other
