package com.worxbend.obs.websocket.client

/** Server-reported connection facts, discovered during the session handshake. */
final case class ConnectionMetadata(
  /** The obs-websocket version the server runs. */
  obsWebSocketVersion:  String,
  /** The RPC version negotiated for this session. */
  negotiatedRpcVersion: Int,
  /** The server's advertised capability set. Typed requests absent from it are rejected locally with
    * [[ObsError.UnsupportedRequest]].
    */
  availableRequests:    Set[String],
)
