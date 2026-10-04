package com.worxbend.obs.websocket.client.transport.sttp

import com.worxbend.obs.websocket.client.{ObsConfig, ObsError, ObsSession}

/** An attempt owns its complete session scope and returns only after that scope closes. */
trait ReconnectConnector:
  def connect[A](config: ObsConfig)(use: ObsSession => A): Either[ObsError, A]
