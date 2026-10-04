package com.worxbend.obs.websocket.client.codegen

private[codegen] final case class SchemaEvent(
    eventType: String,
    dataFields: List[SchemaField],
    initialVersion: String = "",
    description: String = "",
    rpcVersion: String = "",
    deprecated: Boolean = false
)
