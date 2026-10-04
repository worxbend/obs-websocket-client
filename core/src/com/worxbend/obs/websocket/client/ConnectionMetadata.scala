package com.worxbend.obs.websocket.client

final case class ConnectionMetadata(
    obsWebSocketVersion: String,
    negotiatedRpcVersion: Int,
    availableRequests: Set[String]
)
