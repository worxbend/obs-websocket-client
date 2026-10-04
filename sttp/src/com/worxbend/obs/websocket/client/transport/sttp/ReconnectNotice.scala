package com.worxbend.obs.websocket.client.transport.sttp

import com.worxbend.obs.websocket.client.ObsError
import scala.concurrent.duration.FiniteDuration

/** Notices run on the calling thread, outside socket reader/dispatcher workers. */
enum ReconnectNotice:
  case Connected(generation: ConnectionGeneration)
  case EventGap(previous: ConnectionGeneration, cause: ObsError)
  case RetryScheduled(retryNumber: Int, delay: FiniteDuration, cause: ObsError)
  case Reconnected(generation: ConnectionGeneration, previous: ConnectionGeneration)
