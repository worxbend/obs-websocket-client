package com.worxbend.obs.websocket.client.codegen.templates

import com.worxbend.obs.websocket.client.codegen.model.EnumDefinition
import com.worxbend.obs.websocket.client.codegen.schema.Provenance
import SourceFragments.*

/** Source layout for value-preserving enum wrappers and their published constants.
  *
  * Produces `enums/<name>.scala`. Normalization has already chosen String or Long and rewritten any sibling bitmask
  * references. Rendering preserves that constant order and emits a case-class wrapper, allowing unknown future values.
  */
private[codegen] object EnumTemplate:
  /** All enum classification and mask rewriting is complete before this context is rendered. */
  final case class Context(enumeration: EnumDefinition, provenance: Provenance)

  /** Renders constants in upstream declaration order so sibling references retain their semantics. */
  def render(context: Context): String =
    val enumeration = context.enumeration
    val name = enumeration.name
    val provenance = context.provenance
    val valueDoc = seeDoc(s"$name values; unknown values are preserved as data.", name, name, provenance)
    val constantsDoc = seeDoc(s"Published constants for [[$name]].", name, name, provenance)
    val constants = enumeration.constants.map(renderConstant(name, _)).mkString("\n")
    val fileHeader = header(s"$base.enums", provenance, withImport = false)
    s"""$fileHeader${valueDoc}final case class $name(value: ${enumeration.scalaType})
       |
       |${constantsDoc}object $name:
       |$constants
       |""".stripMargin

  private def renderConstant(name: String, constant: EnumDefinition.Constant): String =
    val doc = if constant.description.nonEmpty then s"  /** ${collapse(constant.description)} */\n" else ""
    s"${doc}  val `${constant.identifier}`: $name = $name(${constant.expression})"
