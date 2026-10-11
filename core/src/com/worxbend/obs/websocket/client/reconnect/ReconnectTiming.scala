package com.worxbend.obs.websocket.client.reconnect

import scala.concurrent.duration.FiniteDuration

/** Injected timing makes backoff and stability deterministic in tests and interruptible in production.
  * `monotonicNanos` must use one monotonic nanosecond clock throughout a run; wall-clock time is unsuitable.
  */
final case class ReconnectTiming(
  nextJitter:     () => Double,
  sleep:          FiniteDuration => Unit,
  monotonicNanos: () => Long = () => System.nanoTime(),
)

object ReconnectTiming:
  def live: ReconnectTiming =
    val random = new java.util.Random()
    ReconnectTiming(() => random.nextDouble(), duration => ox.sleep(duration))
