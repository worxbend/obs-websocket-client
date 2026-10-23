package com.worxbend.obs.websocket.client.transport

import com.worxbend.obs.websocket.client.ObsError
import scala.concurrent.duration.*

/** Shared write-deadline default and deadline validation for adapter option classes. */
private[client] object TransportDeadlines:
  val DefaultWrite: FiniteDuration = 10.seconds

  def validate(write: FiniteDuration, readIdle: Option[FiniteDuration]): Either[ObsError, Unit] =
    if write <= Duration.Zero then Left(ObsError.InvalidConfiguration(message = "Write deadline must be positive"))
    else if readIdle.exists(_ <= Duration.Zero) then
      Left(ObsError.InvalidConfiguration(message = "Read idle deadline must be positive"))
    else Right(())
