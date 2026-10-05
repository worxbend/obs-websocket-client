package com.worxbend.obs.websocket.client.codegen.schema

/** Upstream field metadata; optionality and explicit nullability remain distinct. */
private[codegen] final case class SchemaField(
    valueName: String,
    valueType: String,
    valueOptional: Boolean = false,
    valueRestrictions: Option[String] = None,
    valueDescription: String = "",
    valueOptionalBehavior: Option[String] = None
)
