package com.worxbend.obs.websocket.client.codegen.model

/** An enum classified as a String or Long wrapper by schema normalization. Constants retain upstream declaration order
  * because a bitmask may refer to a preceding sibling. Each expression is already quoted or rewritten for Scala;
  * templates must not reclassify values or quote the expression again. The generated wrapper accepts unknown future
  * values rather than restricting decoding to the known constants.
  */
final private[codegen] case class EnumDefinition(
  name:      String,
  scalaType: String,
  constants: List[EnumDefinition.Constant],
)

private[codegen] object EnumDefinition:
  /** An expression is either a quoted string, a numeric literal, or a validated sibling mask. */
  final case class Constant(identifier: String, expression: String, description: String)
