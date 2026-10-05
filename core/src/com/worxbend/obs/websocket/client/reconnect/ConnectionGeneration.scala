package com.worxbend.obs.websocket.client.reconnect

/** A fresh generation within one reconnect run. Failed connection attempts consume a number. */
final case class ConnectionGeneration private[reconnect] (value: Long)
