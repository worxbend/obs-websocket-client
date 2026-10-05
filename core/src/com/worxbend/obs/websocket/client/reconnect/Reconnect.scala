package com.worxbend.obs.websocket.client.reconnect

import com.worxbend.obs.websocket.client.{ObsConfig, ObsError, ObsSession}
import scala.annotation.tailrec
import scala.util.control.NonFatal

/** The backend-agnostic reconnect loop. Each successful attempt invokes a fresh, explicitly identified callback.
  * Requests from old generations are never retained or resubmitted. Applications must recreate their local event
  * subscriptions inside every generation callback. The retry budget and backoff restart whenever a generation's
  * callback completes; only attempts that fail before reaching the callback consume the cumulative budget.
  */
object Reconnect:
  def run[A](
    connector: ReconnectConnector,
    config:    ObsConfig,
    policy:    ReconnectPolicy,
    timing:    ReconnectTiming,
    onNotice:  ReconnectNotice => Unit,
  )(use: (ConnectionGeneration, ObsSession) => ReconnectDecision[A]): Either[ObsError, A] =
    config.validate.flatMap: valid =>
      try
        @tailrec
        def loop(state: State): Either[ObsError, A] =
          val generation = ConnectionGeneration(value = state.generations + 1L)
          val attempt    = connector.connect(config = state.config): session =>
            state.previous match
              case None => notify(onNotice = onNotice, notice = ReconnectNotice.Connected(generation = generation))
              case Some(previous) =>
                notify(
                  onNotice = onNotice,
                  notice   = ReconnectNotice.Reconnected(generation = generation, previous = previous),
                )
            use(generation, session)
          val step = attempt match
            case Left(error) =>
              retry(error = error, state = state, policy = policy, timing = timing, onNotice = onNotice, gap = None)
            case Right(ReconnectDecision.Complete(value))       => Step.Done(result = Right(value))
            case Right(ReconnectDecision.Stop(error))           => Step.Done(result = Left(error))
            case Right(ReconnectDecision.Retry(error, desired)) =>
              // The use callback completed, so the connection was healthy: restart both the retry budget and the
              // delay progression. Only attempts that never reached the callback consume the cumulative budget.
              retry(
                error = error,
                state = state.copy(
                  config   = state.config.copy(eventSubscriptions = desired),
                  retries  = 0,
                  previous = Some(generation),
                ),
                policy   = policy,
                timing   = timing,
                onNotice = onNotice,
                gap      = Some(ReconnectNotice.EventGap(generation = generation, cause = error)),
              )
          step match
            case Step.Done(value) => value
            case Step.Again(next) => loop(state = next)
        loop(state = State(config = valid, retries = 0, previous = None, generations = 0L))
      catch
        case NoticeFailure(cause) =>
          Left(ObsError.InternalError(message = s"Reconnect notice callback failed: ${cause.getMessage}"))

  /** The event gap notice is emitted only once a retry is certain, immediately before its `RetryScheduled`, so a gap is
    * never reported without a following retry.
    */
  private def retry(
    error:    ObsError,
    state:    State,
    policy:   ReconnectPolicy,
    timing:   ReconnectTiming,
    onNotice: ReconnectNotice => Unit,
    gap:      Option[ReconnectNotice.EventGap],
  ): Step[Nothing] =
    if state.retries >= policy.maxRetries || !ReconnectPolicy.retryable(error = error) then
      Step.Done(result = Left(error))
    else
      policy.delay(retry = state.retries, sample = timing.nextJitter()) match
        case Left(invalid) => Step.Done(result = Left(invalid))
        case Right(delay)  =>
          val next = state.copy(retries = state.retries + 1, generations = state.generations + 1L)
          gap.foreach(notify(onNotice = onNotice, _))
          notify(
            onNotice = onNotice,
            notice   = ReconnectNotice.RetryScheduled(retryNumber = next.retries, delay = delay, cause = error),
          )
          timing.sleep(delay)
          Step.Again(state = next)

  /** Notice callback failures must not escape the `Either` contract as raw throws, so they are wrapped and surfaced as
    * `ObsError.InternalError`. Interruption propagates untouched — `NonFatal` already excludes it — so cancellation
    * stays responsive.
    */
  private def notify(onNotice: ReconnectNotice => Unit, notice: ReconnectNotice): Unit =
    try onNotice(notice)
    catch case NonFatal(cause) => throw NoticeFailure(cause = cause)

  final private case class NoticeFailure(cause: Throwable) extends RuntimeException(cause)

  final private case class State(
    config:      ObsConfig,
    retries:     Int,
    previous:    Option[ConnectionGeneration],
    generations: Long,
  )
  private enum Step[+A]:
    case Done(result: Either[ObsError, A])
    case Again(state: State)
