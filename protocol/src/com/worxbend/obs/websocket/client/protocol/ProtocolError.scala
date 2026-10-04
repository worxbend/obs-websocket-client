package com.worxbend.obs.websocket.client.protocol

/** Safe structural decode failure. Payloads and credentials are excluded. */
final case class ProtocolError(path: String, message: String)
