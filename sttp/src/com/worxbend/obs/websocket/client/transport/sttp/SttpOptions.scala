package com.worxbend.obs.websocket.client.transport.sttp

import com.worxbend.obs.websocket.client.ObsError
import scala.concurrent.duration.*

/** Transport write deadline includes backend serialization; timing out destroys this connection. */
final case class SttpOptions(
    writeTimeout: FiniteDuration = 10.seconds,
    headers: HandshakeHeaders = HandshakeHeaders.empty
):
  def validate: Either[ObsError, SttpOptions] =
    if writeTimeout <= Duration.Zero then Left(ObsError.InvalidConfiguration("Write deadline must be positive"))
    else Right(this)

  override def toString: String = s"SttpOptions(writeTimeout=$writeTimeout, headers=<redacted>)"
