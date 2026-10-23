package com.worxbend.obs.websocket.client.transport.sttp

import com.worxbend.obs.websocket.client.ObsError
import com.worxbend.obs.websocket.client.transport.{HandshakeHeaders, TransportDeadlines}
import scala.concurrent.duration.FiniteDuration

/** Transport write deadline includes backend serialization; timing out destroys this connection. `readIdleTimeout` is
  * opt-in liveness detection: when set, any receive that waits longer than the deadline for wire traffic — including a
  * stall between the fragments of one message — fails with a retryable timeout and destroys the connection, so
  * half-open connections (lost FIN, NAT timeout) engage `ReconnectPolicy` classification instead of stalling forever.
  */
final case class SttpOptions(
  writeTimeout:    FiniteDuration = SttpOptions.DefaultWriteTimeout,
  headers:         HandshakeHeaders = HandshakeHeaders.empty,
  readIdleTimeout: Option[FiniteDuration] = None,
):
  def validate: Either[ObsError, SttpOptions] =
    TransportDeadlines.validate(write = writeTimeout, readIdle = readIdleTimeout).map(_ => this)

  override def toString: String =
    s"SttpOptions(writeTimeout=$writeTimeout, headers=<redacted>, readIdleTimeout=$readIdleTimeout)"

object SttpOptions:
  /** Single source for the transport write deadline default, shared with the transport constructor. */
  val DefaultWriteTimeout: FiniteDuration = TransportDeadlines.DefaultWrite
