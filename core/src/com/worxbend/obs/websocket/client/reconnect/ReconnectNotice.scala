package com.worxbend.obs.websocket.client.reconnect

import com.worxbend.obs.websocket.client.ObsError
import scala.concurrent.duration.FiniteDuration

/** Notices run on the calling thread, outside socket reader/dispatcher workers. `EventGap` names the generation that
  * just failed and fires only when a retry actually follows.
  */
enum ReconnectNotice:
  case Connected(generation: ConnectionGeneration)
  case EventGap(generation: ConnectionGeneration, cause: ObsError)
  case RetryScheduled(retryNumber: Int, delay: FiniteDuration, cause: ObsError)
  case Reconnected(generation: ConnectionGeneration, previous: ConnectionGeneration)
