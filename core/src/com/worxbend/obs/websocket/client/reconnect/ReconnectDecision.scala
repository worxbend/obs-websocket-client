package com.worxbend.obs.websocket.client.reconnect

import com.worxbend.obs.websocket.client.{EventSubscriptions, ObsError}

/** Retry explicitly authorizes a new generation callback, never a replay of pending requests. */
enum ReconnectDecision[+A]:
  case Complete(value: A)
  case Stop(error: ObsError)
  case Retry(cause: ObsError, desiredSubscriptions: EventSubscriptions)
