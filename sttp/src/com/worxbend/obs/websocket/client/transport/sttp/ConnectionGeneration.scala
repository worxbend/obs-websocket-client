package com.worxbend.obs.websocket.client.transport.sttp

/** A fresh generation within one reconnect run. Failed connection attempts consume a number. */
final case class ConnectionGeneration private[sttp] (value: Long)
