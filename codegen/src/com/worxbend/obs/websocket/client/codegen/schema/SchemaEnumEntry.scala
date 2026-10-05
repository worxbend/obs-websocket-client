package com.worxbend.obs.websocket.client.codegen.schema

/** One named upstream enum value and its documentation. */
final private[codegen] case class SchemaEnumEntry(
  enumIdentifier: String,
  enumValue:      SchemaEnumValue,
  description:    String = "",
)
