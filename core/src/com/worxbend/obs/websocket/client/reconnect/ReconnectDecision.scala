package com.worxbend.obs.websocket.client.reconnect

import com.worxbend.obs.websocket.client.{EventSubscriptions, ObsError}

/** Retry explicitly authorizes a new generation callback, never a replay of pending requests. */
enum ReconnectDecision[+A]:
  /** The callback finished; end the run with its value. */
  case Complete(value: A)

  /** Abort the run with this error; no further retries. */
  case Stop(error: ObsError)

  case Retry(cause: ObsError, desiredSubscriptions: EventSubscriptions)
