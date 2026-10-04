package com.worxbend.obs.websocket.client

import scala.concurrent.duration.*

/** A complete exchange budget, including registration and waiting for its response. */
final case class RequestOptions(timeout: Option[FiniteDuration] = None):
  private[client] def deadline(default: FiniteDuration): Either[ObsError, FiniteDuration] =
    val value = timeout.getOrElse(default)
    if value <= Duration.Zero then Left(ObsError.InvalidConfiguration("Request timeout must be positive"))
    else Right(value)
