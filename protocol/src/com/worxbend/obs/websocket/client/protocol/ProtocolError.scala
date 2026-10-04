package com.worxbend.obs.websocket.client.protocol

/** Safe structural decode failure. Payloads and credentials are excluded. */
final case class ProtocolError(path: String, message: String)

object ProtocolError:
  /** Distinct instance for byte-limit rejections, so callers can classify oversize separately from malformed input. */
  val SizeLimit: ProtocolError = ProtocolError("$", "JSON exceeds configured byte limit")
