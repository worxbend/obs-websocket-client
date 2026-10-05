package com.worxbend.obs.websocket.client.reconnect

import com.worxbend.obs.websocket.client.ObsError
import scala.concurrent.duration.*

/** A bounded retry budget for the explicitly selected reconnect entrypoint. */
final class ReconnectPolicy private (
  val maxRetries:     Int,
  val initialDelay:   FiniteDuration,
  val maxDelay:       FiniteDuration,
  val jitterFraction: Double,
):
  private[client] def delay(retry: Int, sample: Double): Either[ObsError, FiniteDuration] =
    if !sample.isFinite || sample < 0.0 || sample > 1.0 then
      Left(ObsError.InvalidConfiguration(message = "Reconnect jitter sample must be finite and between zero and one"))
    else
      val exponential = (BigInt(initialDelay.toNanos) << math.min(retry, 63)).min(BigInt(maxDelay.toNanos))
      val factor      = BigDecimal(1.0 - jitterFraction) + BigDecimal(2.0 * jitterFraction) * BigDecimal(sample)
      val nanos       = (BigDecimal(exponential) * factor).toBigInt.min(BigInt(maxDelay.toNanos)).longValue
      Right(nanos.nanos)

object ReconnectPolicy:
  def create(
    maxRetries:     Int = 5,
    initialDelay:   FiniteDuration = 250.millis,
    maxDelay:       FiniteDuration = 10.seconds,
    jitterFraction: Double = 0.2,
  ): Either[ObsError, ReconnectPolicy] =
    if maxRetries < 0 then Left(ObsError.InvalidConfiguration(message = "Reconnect retry count must be nonnegative"))
    else if initialDelay <= Duration.Zero || maxDelay < initialDelay then
      Left(
        ObsError.InvalidConfiguration(message =
          "Reconnect delays must be positive and maximum must cover initial delay"
        )
      )
    else if !jitterFraction.isFinite || jitterFraction < 0.0 || jitterFraction > 1.0 then
      Left(ObsError.InvalidConfiguration(message = "Reconnect jitter fraction must be finite and between zero and one"))
    else
      Right(
        new ReconnectPolicy(
          maxRetries     = maxRetries,
          initialDelay   = initialDelay,
          maxDelay       = maxDelay,
          jitterFraction = jitterFraction,
        )
      )

  /** Only genuinely transient failures retry: a transport failure without a close code, a transient close code (1001,
    * 1006, 1011, 1012, 1013), or a local timeout. Deterministic conditions — `MessageTooLarge`, `UnsupportedMessage`,
    * `InvalidConfiguration`, `InternalError`, malformed payloads, authentication and protocol failures — recur
    * identically after reconnect and therefore never retry. The terminal OBS close codes (1000, 4009, 4010, 4011) never
    * retry either.
    */
  private[client] def retryable(error: ObsError): Boolean = error match
    case ObsError.Transport(_, None)       => true
    case ObsError.Transport(_, Some(code)) => Set(1001, 1006, 1011, 1012, 1013).contains(code)
    case ObsError.Timeout(_)               => true
    case _                                 => false
