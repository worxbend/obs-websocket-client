package com.worxbend.obs.websocket.client.codegen

private[codegen] final case class SchemaEnumEntry(
    enumIdentifier: String,
    enumValue: SchemaEnumValue,
    description: String = ""
)
