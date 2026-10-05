package com.worxbend.obs.websocket.client.reconnect

import scala.concurrent.duration.FiniteDuration

/** Injected timing makes backoff deterministic in tests and interruptible in production. */
final case class ReconnectTiming(nextJitter: () => Double, sleep: FiniteDuration => Unit)

object ReconnectTiming:
  def live: ReconnectTiming =
    val random = new java.util.Random()
    ReconnectTiming(() => random.nextDouble(), duration => ox.sleep(duration))
