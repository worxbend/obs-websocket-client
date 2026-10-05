package com.worxbend.obs.websocket.client.transport.fs2

import com.worxbend.obs.websocket.client.reconnect.{
  ConnectionGeneration,
  Reconnect,
  ReconnectConnector,
  ReconnectDecision,
  ReconnectNotice,
  ReconnectPolicy,
  ReconnectTiming,
}
import com.worxbend.obs.websocket.client.{ObsConfig, ObsError, ObsSession}

/** Opt-in reconnect over the fs2 backend. Each successful attempt invokes a fresh, explicitly identified callback.
  * Requests from old generations are never retained or resubmitted. Applications must recreate their local event
  * subscriptions inside every generation callback. The retry budget and backoff restart whenever a generation's
  * callback completes; only attempts that fail before reaching the callback consume the cumulative budget. The
  * backend-agnostic loop lives in [[com.worxbend.obs.websocket.client.reconnect.Reconnect]].
  */
object Fs2ReconnectingObsClient:
  def run[A](
    config:   ObsConfig,
    policy:   ReconnectPolicy,
    timing:   ReconnectTiming = ReconnectTiming.live,
    onNotice: ReconnectNotice => Unit = _ => (),
    options:  Fs2Options = Fs2Options(),
  )(use: (ConnectionGeneration, ObsSession) => ReconnectDecision[A]): Either[ObsError, A] =
    options.validate.flatMap: validOptions =>
      val connector = new ReconnectConnector:
        def connect[B](attemptConfig: ObsConfig)(consume: ObsSession => B): Either[ObsError, B] =
          Fs2ObsClient.connect(config = attemptConfig, options = validOptions)(use = consume)
      Reconnect.run(connector = connector, config = config, policy = policy, timing = timing, onNotice = onNotice)(use =
        use
      )
