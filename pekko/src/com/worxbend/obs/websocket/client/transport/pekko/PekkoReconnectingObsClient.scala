package com.worxbend.obs.websocket.client.transport.pekko

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
import org.apache.pekko.actor.ActorSystem
import ox.{resourceScope, useInScope}

/** Opt-in reconnect over the Pekko backend. Each successful attempt invokes a fresh, explicitly identified callback.
  * Requests from old generations are never retained or resubmitted. Applications must recreate their local event
  * subscriptions inside every generation callback. The retry budget and backoff restart whenever a generation's
  * callback completes; only attempts that fail before reaching the callback consume the cumulative budget. One private
  * actor system serves every attempt and terminates (bounded 5 s wait) when `run` returns; invalid config or options
  * build none. The backend-agnostic loop lives in [[com.worxbend.obs.websocket.client.reconnect.Reconnect]].
  */
object PekkoReconnectingObsClient:
  def run[A](
    config:   ObsConfig,
    policy:   ReconnectPolicy,
    timing:   ReconnectTiming = ReconnectTiming.live,
    onNotice: ReconnectNotice => Unit = _ => (),
    options:  PekkoOptions = PekkoOptions(),
  )(use: (ConnectionGeneration, ObsSession) => ReconnectDecision[A]): Either[ObsError, A] =
    config.validate.flatMap(_ => options.validate).flatMap: validOptions =>
      // One owned system for the whole run; each attempt aborts only its own transport.
      resourceScope:
        val system    = useInScope(ActorSystem(name = PekkoObsClient.newSystemName()))(PekkoObsClient.shutdownSystem(_))
        val connector = new ReconnectConnector:
          def connect[B](attemptConfig: ObsConfig)(consume: ObsSession => B): Either[ObsError, B] =
            PekkoObsClient.withNativeSystem(system = system, config = attemptConfig, options = validOptions)(use =
              consume
            )
        Reconnect.run(connector = connector, config = config, policy = policy, timing = timing, onNotice = onNotice)(
          use = use
        )
