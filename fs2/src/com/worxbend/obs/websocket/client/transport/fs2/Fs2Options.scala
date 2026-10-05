package com.worxbend.obs.websocket.client.transport.fs2

import com.worxbend.obs.websocket.client.transport.HandshakeHeaders
import com.worxbend.obs.websocket.client.ObsError
import scala.concurrent.duration.*

/** Transport write deadline includes backend serialization; timing out destroys this connection. `readIdleTimeout` is
  * opt-in liveness detection: when set, any receive that waits longer than the deadline for wire traffic — including a
  * stall between the fragments of one message — fails with a retryable timeout and destroys the connection, so
  * half-open connections (lost FIN, NAT timeout) engage `ReconnectPolicy` classification instead of stalling forever.
  */
final case class Fs2Options(
  writeTimeout:    FiniteDuration = Fs2Options.DefaultWriteTimeout,
  headers:         HandshakeHeaders = HandshakeHeaders.empty,
  readIdleTimeout: Option[FiniteDuration] = None,
):
  def validate: Either[ObsError, Fs2Options] =
    if writeTimeout <= Duration.Zero then
      Left(ObsError.InvalidConfiguration(message = "Write deadline must be positive"))
    else if readIdleTimeout.exists(_ <= Duration.Zero) then
      Left(ObsError.InvalidConfiguration(message = "Read idle deadline must be positive"))
    else Right(this)

  override def toString: String =
    s"Fs2Options(writeTimeout=$writeTimeout, headers=<redacted>, readIdleTimeout=$readIdleTimeout)"

object Fs2Options:
  /** Single source for the transport write deadline default, shared with the transport constructor. */
  val DefaultWriteTimeout: FiniteDuration = 10.seconds
