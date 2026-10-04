package com.worxbend.obs.websocket.client.transport.sttp

import com.worxbend.obs.websocket.client.{ObsConfig, ObsError, ObsSession}
import scala.util.control.NonFatal

/** Opt-in reconnect. Each successful attempt invokes a fresh, explicitly identified callback. Requests from old
  * generations are never retained or resubmitted. Applications must recreate their local event subscriptions inside
  * every generation callback.
  */
object ReconnectingObsClient:
  def run[A](
      config: ObsConfig,
      policy: ReconnectPolicy,
      timing: ReconnectTiming = ReconnectTiming.live,
      onNotice: ReconnectNotice => Unit = _ => (),
      options: SttpOptions = SttpOptions(),
      clientOptions: JdkClientOptions = JdkClientOptions()
  )(use: (ConnectionGeneration, ObsSession) => ReconnectDecision[A]): Either[ObsError, A] =
    options.validate.flatMap: validOptions =>
      val connector = new ReconnectConnector:
        def connect[B](attemptConfig: ObsConfig)(consume: ObsSession => B): Either[ObsError, B] =
          SttpObsClient.connect(attemptConfig, validOptions, clientOptions)(consume)
      withConnector(connector, config, policy, timing, onNotice)(use)

  def withConnector[A](
      connector: ReconnectConnector,
      config: ObsConfig,
      policy: ReconnectPolicy,
      timing: ReconnectTiming,
      onNotice: ReconnectNotice => Unit
  )(use: (ConnectionGeneration, ObsSession) => ReconnectDecision[A]): Either[ObsError, A] =
    config.validate.flatMap: valid =>
      try
        var state = State(valid, 0, None)
        var result = Option.empty[Either[ObsError, A]]
        while result.isEmpty do
          val generation = ConnectionGeneration(state.retries.toLong + 1L)
          val attempt = connector.connect(state.config): session =>
            state.previous match
              case None           => notify(onNotice, ReconnectNotice.Connected(generation))
              case Some(previous) => notify(onNotice, ReconnectNotice.Reconnected(generation, previous))
            use(generation, session)
          val step = attempt match
            case Left(error)                                    => retry(error, state, policy, timing, onNotice, None)
            case Right(ReconnectDecision.Complete(value))       => Step.Done(Right(value))
            case Right(ReconnectDecision.Stop(error))           => Step.Done(Left(error))
            case Right(ReconnectDecision.Retry(error, desired)) =>
              retry(
                error,
                state.copy(config = state.config.copy(eventSubscriptions = desired), previous = Some(generation)),
                policy,
                timing,
                onNotice,
                Some(ReconnectNotice.EventGap(generation, error))
              )
          step match
            case Step.Done(value) => result = Some(value)
            case Step.Again(next) => state = next
        result.get
      catch
        case NoticeFailure(cause) =>
          Left(ObsError.InternalError(s"Reconnect notice callback failed: ${cause.getMessage}"))

  /** The event gap notice is emitted only once a retry is certain, immediately before its `RetryScheduled`, so a gap is
    * never reported without a following retry.
    */
  private def retry(
      error: ObsError,
      state: State,
      policy: ReconnectPolicy,
      timing: ReconnectTiming,
      onNotice: ReconnectNotice => Unit,
      gap: Option[ReconnectNotice.EventGap]
  ): Step[Nothing] =
    if state.retries >= policy.maxRetries || !ReconnectPolicy.retryable(error) then Step.Done(Left(error))
    else
      policy.delay(state.retries, timing.nextJitter()) match
        case Left(invalid) => Step.Done(Left(invalid))
        case Right(delay)  =>
          val next = state.copy(retries = state.retries + 1)
          gap.foreach(notify(onNotice, _))
          notify(onNotice, ReconnectNotice.RetryScheduled(next.retries, delay, error))
          timing.sleep(delay)
          Step.Again(next)

  /** Notice callback failures must not escape the `Either` contract as raw throws, so they are wrapped and surfaced as
    * `ObsError.InternalError`. Interruption propagates untouched — `NonFatal` already excludes it — so cancellation
    * stays responsive.
    */
  private def notify(onNotice: ReconnectNotice => Unit, notice: ReconnectNotice): Unit =
    try onNotice(notice)
    catch case NonFatal(cause) => throw NoticeFailure(cause)

  private final case class NoticeFailure(cause: Throwable) extends RuntimeException(cause)

  private final case class State(config: ObsConfig, retries: Int, previous: Option[ConnectionGeneration])
  private enum Step[+A]:
    case Done(result: Either[ObsError, A])
    case Again(state: State)
