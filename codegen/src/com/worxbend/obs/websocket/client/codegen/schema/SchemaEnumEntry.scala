package com.worxbend.obs.websocket.client.codegen.schema

/** One named upstream enum value and its documentation. */
private[codegen] final case class SchemaEnumEntry(
    enumIdentifier: String,
    enumValue: SchemaEnumValue,
    description: String = ""
)
