package com.worxbend.obs.websocket.client

import java.util.concurrent.atomic.AtomicLong

/** Connection-local ordinal avoids historical-ID storage and makes even repeated injected seeds safe.
  * Share this instance between discovery and the public session facade. Exhaustion never wraps back to an issued ID.
  */
final private[client] class CorrelationIds(initialOrdinal: Long = 0L):
  private val ordinal = new AtomicLong(initialOrdinal)

  def next(seed: String): Either[ObsError, String] =
    val value = ordinal.updateAndGet(current => if current == Long.MaxValue then current else current + 1L)
    if value == Long.MaxValue then Left(ObsError.InternalError(message = "Connection correlation IDs exhausted"))
    else Right(s"$seed:$value")
