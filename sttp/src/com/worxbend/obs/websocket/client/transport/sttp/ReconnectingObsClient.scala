package com.worxbend.obs.websocket.client.transport.sttp

import com.worxbend.obs.websocket.client.{ObsConfig, ObsError, ObsSession}

/** Opt-in reconnect. Each successful attempt invokes a fresh, explicitly identified callback. Requests from old
  * generations are never retained or resubmitted. Applications must recreate their local event subscriptions inside
  * every generation callback.
  */
object ReconnectingObsClient:
  private val sttpConnector: ReconnectConnector = new ReconnectConnector:
    def connect[A](config: ObsConfig)(use: ObsSession => A): Either[ObsError, A] = SttpObsClient.connect(config)(use)

  def run[A](
      config: ObsConfig,
      policy: ReconnectPolicy,
      timing: ReconnectTiming = ReconnectTiming.live,
      onNotice: ReconnectNotice => Unit = _ => ()
  )(use: (ConnectionGeneration, ObsSession) => ReconnectDecision[A]): Either[ObsError, A] =
    withConnector(sttpConnector, config, policy, timing, onNotice)(use)

  def withConnector[A](
      connector: ReconnectConnector,
      config: ObsConfig,
      policy: ReconnectPolicy,
      timing: ReconnectTiming,
      onNotice: ReconnectNotice => Unit
  )(use: (ConnectionGeneration, ObsSession) => ReconnectDecision[A]): Either[ObsError, A] =
    config.validate.flatMap: valid =>
      var state = State(valid, 0, None)
      var result = Option.empty[Either[ObsError, A]]
      while result.isEmpty do
        val generation = ConnectionGeneration(state.retries.toLong + 1L)
        val attempt = connector.connect(state.config): session =>
          state.previous match
            case None           => onNotice(ReconnectNotice.Connected(generation))
            case Some(previous) => onNotice(ReconnectNotice.Reconnected(generation, previous))
          use(generation, session)
        val step = attempt match
          case Left(error)                                    => retry(error, state, policy, timing, onNotice)
          case Right(ReconnectDecision.Complete(value))       => Step.Done(Right(value))
          case Right(ReconnectDecision.Stop(error))           => Step.Done(Left(error))
          case Right(ReconnectDecision.Retry(error, desired)) =>
            onNotice(ReconnectNotice.EventGap(generation, error))
            retry(
              error,
              state.copy(config = state.config.copy(eventSubscriptions = desired), previous = Some(generation)),
              policy,
              timing,
              onNotice
            )
        step match
          case Step.Done(value) => result = Some(value)
          case Step.Again(next) => state = next
      result.get

  private def retry(
      error: ObsError,
      state: State,
      policy: ReconnectPolicy,
      timing: ReconnectTiming,
      onNotice: ReconnectNotice => Unit
  ): Step[Nothing] =
    if state.retries >= policy.maxRetries || !ReconnectPolicy.retryable(error) then Step.Done(Left(error))
    else
      policy.delay(state.retries, timing.nextJitter()) match
        case Left(invalid) => Step.Done(Left(invalid))
        case Right(delay)  =>
          val next = state.copy(retries = state.retries + 1)
          onNotice(ReconnectNotice.RetryScheduled(next.retries, delay, error))
          timing.sleep(delay)
          Step.Again(next)

  private final case class State(config: ObsConfig, retries: Int, previous: Option[ConnectionGeneration])
  private enum Step[+A]:
    case Done(result: Either[ObsError, A])
    case Again(state: State)
