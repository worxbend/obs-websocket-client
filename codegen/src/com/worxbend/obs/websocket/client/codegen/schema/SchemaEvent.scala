package com.worxbend.obs.websocket.client.codegen.schema

/** Upstream event payload description. */
final private[codegen] case class SchemaEvent(
  eventType:      String,
  dataFields:     List[SchemaField],
  initialVersion: String = "",
  description:    String = "",
  rpcVersion:     String = "",
  deprecated:     Boolean = false,
)
