package com.worxbend.obs.websocket.client.codegen.schema

/** Upstream request and response payload descriptions. */
private[codegen] final case class SchemaRequest(
    requestType: String,
    requestFields: List[SchemaField],
    responseFields: List[SchemaField],
    initialVersion: String = "",
    description: String = "",
    rpcVersion: String = "",
    deprecated: Boolean = false,
    category: String = "general"
)
