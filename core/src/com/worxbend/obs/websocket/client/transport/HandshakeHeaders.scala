package com.worxbend.obs.websocket.client.transport

import com.worxbend.obs.websocket.client.ObsError
import java.util.Locale

/** Additional upgrade headers. Values are never included in diagnostics or rendering. */
final class HandshakeHeaders private (private[client] val entries: Vector[(String, String)]):
  override def toString: String = "HandshakeHeaders(<redacted>)"

object HandshakeHeaders:
  val empty: HandshakeHeaders = new HandshakeHeaders(entries = Vector.empty)

  /** Rejects invalid HTTP characters and headers owned by the WebSocket handshake. */
  def create(entries: Vector[(String, String)]): Either[ObsError, HandshakeHeaders] =
    val reserved = Set("connection", "upgrade", "host", "content-length", "transfer-encoding", "expect")
    val valid    = entries.forall: (name, value) =>
      val lower = name.toLowerCase(Locale.ROOT)
      name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") &&
      !reserved.contains(lower) && !lower.startsWith("sec-websocket-") &&
      value.forall(c => c == '\t' || (c >= ' ' && c <= '~'))
    if valid then Right(new HandshakeHeaders(entries = entries))
    else Left(ObsError.InvalidConfiguration(message = "Invalid or reserved WebSocket handshake header"))
