package com.worxbend.obs.websocket.client.codegen.model

/** A homogeneous enum whose constants have validated Scala expressions. */
private[codegen] final case class EnumDefinition(
    name: String,
    scalaType: String,
    constants: List[EnumDefinition.Constant]
)

private[codegen] object EnumDefinition:
  /** An expression is either a quoted string, a numeric literal, or a validated sibling mask. */
  final case class Constant(identifier: String, expression: String, description: String)
