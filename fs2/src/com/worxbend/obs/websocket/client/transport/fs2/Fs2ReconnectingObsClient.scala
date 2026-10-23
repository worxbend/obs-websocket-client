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
import com.worxbend.obs.websocket.client.transport.JdkClientOptions
import com.worxbend.obs.websocket.client.{ObsConfig, ObsError, ObsSession}

/** Opt-in reconnect over the fs2 backend. Each successful attempt invokes a fresh, explicitly identified callback.
  * Requests from old generations are never retained or resubmitted. Applications must recreate their local event
  * subscriptions inside every generation callback. The retry budget and backoff restart whenever a generation's
  * callback completes; only attempts that fail before reaching the callback consume the cumulative budget. The
  * backend-agnostic loop lives in [[com.worxbend.obs.websocket.client.reconnect.Reconnect]].
  */
object Fs2ReconnectingObsClient:
  /** Owned-client settings (`clientOptions`, proxy/TLS) reused for every new connection generation. */
  def run[A](
    config:        ObsConfig,
    policy:        ReconnectPolicy,
    timing:        ReconnectTiming = ReconnectTiming.live,
    onNotice:      ReconnectNotice => Unit = _ => (),
    options:       Fs2Options = Fs2Options(),
    clientOptions: JdkClientOptions = JdkClientOptions(),
  )(use: (ConnectionGeneration, ObsSession) => ReconnectDecision[A]): Either[ObsError, A] =
    options.validate.flatMap: validOptions =>
      Reconnect.run(
        connector = connector(options = validOptions, clientOptions = clientOptions),
        config    = config,
        policy    = policy,
        timing    = timing,
        onNotice  = onNotice,
      )(use = use)

  private[fs2] trait ClientConnector:
    def connect[A](config: ObsConfig, options: Fs2Options, clientOptions: JdkClientOptions)(
      use: ObsSession => A
    ): Either[ObsError, A]

  private val live: ClientConnector = new ClientConnector:
    def connect[A](config: ObsConfig, options: Fs2Options, clientOptions: JdkClientOptions)(
      use: ObsSession => A
    ): Either[ObsError, A] =
      Fs2ObsClient.connect(config = config, options = options, clientOptions = clientOptions)(use = use)

  private[fs2] def connector(
    options:       Fs2Options,
    clientOptions: JdkClientOptions,
    factory:       ClientConnector = live,
  ): ReconnectConnector = new ReconnectConnector:
    def connect[A](attemptConfig: ObsConfig)(consume: ObsSession => A): Either[ObsError, A] =
      factory.connect(config = attemptConfig, options = options, clientOptions = clientOptions)(use = consume)
