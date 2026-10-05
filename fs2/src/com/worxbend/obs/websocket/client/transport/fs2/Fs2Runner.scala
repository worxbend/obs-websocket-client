package com.worxbend.obs.websocket.client.transport.fs2

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import java.util.concurrent.LinkedBlockingQueue

/** Runs backend effects on the shared global cats-effect runtime while presenting the blocking `ObsTransport` seam.
  * Each effect runs via `unsafeRunCancelable`; the calling (virtual) thread parks on a one-shot queue fed by the
  * materialized `attempt` result, so a blocked wait is fully interruptible: interruption cancels the underlying
  * cats-effect fiber and the `InterruptedException` propagates with no status loss. Ox `fork`/`timeoutOption`
  * supervision therefore composes with every transport operation exactly as it does for the sync backends. Results
  * travel through `attempt`'s `Either` channel rather than a `scala.concurrent.Promise`, so the fatal-boxing
  * (`ExecutionException`) issue of the ZIO runner does not arise. A foreign effect interrupted mid-flight completes on
  * cats-effect worker threads that Ox scope joins cannot await, mirroring the ZIO module's teardown characteristics.
  */
private[fs2] object Fs2Runner:
  given IORuntime = IORuntime.global

  def await[A](effect: IO[A]): A =
    val completion = new LinkedBlockingQueue[Either[Throwable, A]]()
    val cancel     = effect.attempt
      .flatMap(result => IO(completion.put(result)).void)
      .unsafeRunCancelable()
    val outcome =
      try completion.take()
      catch
        case interrupted: InterruptedException =>
          val _ = cancel()
          throw interrupted
    outcome match
      case Right(value) => value
      case Left(error)  => throw error
