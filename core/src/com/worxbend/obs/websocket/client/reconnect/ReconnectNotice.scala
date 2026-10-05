package com.worxbend.obs.websocket.client.reconnect

import com.worxbend.obs.websocket.client.ObsError
import scala.concurrent.duration.FiniteDuration

/** Notices run on the calling thread, outside socket reader/dispatcher workers. `EventGap` names the generation that
  * just failed and fires only when a retry actually follows.
  */
enum ReconnectNotice:
  /** A new generation connected and identified successfully. */
  case Connected(generation: ConnectionGeneration)

  /** The named generation failed and its remaining events are lost; fires only when a retry actually follows. */
  case EventGap(generation: ConnectionGeneration, cause: ObsError)

  /** A retry attempt will start after `delay`. */
  case RetryScheduled(retryNumber: Int, delay: FiniteDuration, cause: ObsError)

  /** A retry established `generation`, replacing the failed `previous` one. */
  case Reconnected(generation: ConnectionGeneration, previous: ConnectionGeneration)
