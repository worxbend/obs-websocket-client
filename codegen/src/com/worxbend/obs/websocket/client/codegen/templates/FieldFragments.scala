package com.worxbend.obs.websocket.client.codegen.templates

import com.worxbend.obs.websocket.client.codegen.model.Field
import com.worxbend.obs.websocket.client.codegen.ScalaLiteral.quote

/** Shared payload syntax. Expressions have no trailing newline; decoder bodies start with a newline and carry their own
  * four-space indentation. Insert them directly after a method's `= `.
  *
  * A normalized Field stores a base type and codec; these fragments apply the optional/nullable wrappers consistently
  * to constructor parameters, JSON encoders, and decoders. Wire keys are quoted as Scala literals, while member names
  * are backticked. Dotted wire keys select NestedFields operations rather than literal dotted JSON properties. The
  * source snippets reference the generated protocol API, not this generator's own model.Field.
  */
private[codegen] object FieldFragments:
  /** Constructor or facade parameters, including defaults for omitted fields. */
  def parameters(fields: List[Field]): String = fields
    .map: field =>
      val tpe = if field.optional then s"Field[${field.scalaType}]"
      else if field.nullable then s"Option[${field.scalaType}]"
      else field.scalaType
      val default = if field.optional then " = Field.Missing" else ""
      s"`${field.identifier}`: $tpe$default"
    .mkString(", ")

  private def codec(field: Field): String =
    if field.nullable && !field.optional then s"ValueCodec.nullable(${field.codec})" else field.codec

  /** Encodes normalized wire keys, using nested-object support only for dotted fields. */
  def encode(fields: List[Field]): String =
    if fields.isEmpty then "JsonObject.empty"
    else
      val constructor = if fields.exists(_.name.contains('.')) then "NestedFields.encode" else "JsonObject"
      val entries = fields.map: field =>
        if field.optional then s"ValueCodec.put(${quote(field.name)}, `${field.identifier}`, ${field.codec})"
        else s"Map(${quote(field.name)} -> ${codec(field)}.encode(`${field.identifier}`))"
      s"$constructor(${entries.mkString(" ++ ")})"

  /** Decoder body with its own newline and indentation; unknown future fields remain accepted. */
  def decode(name: String, fields: List[Field]): String =
    if fields.isEmpty then s"""
         |    val _ = data // Empty payloads deliberately accept unknown future fields.
         |    Right($name())""".stripMargin
    else
      val reads = fields.map: field =>
        val read = if field.optional && field.name.contains('.') then
          s"NestedFields.field(data, ${quote(field.name)}, ${field.codec}, ${field.nullable})"
        else if field.optional then s"data.field(${quote(field.name)}, ${field.codec}, ${field.nullable})"
        else if field.name.contains('.') then s"NestedFields.required(data, ${quote(field.name)}, ${codec(field)})"
        else s"data.required(${quote(field.name)}, ${codec(field)})"
        s"      `${field.identifier}` <- $read"
      s"""
         |    for
         |${reads.mkString("\n")}
         |    yield $name(${arguments(fields)})""".stripMargin

  /** Positional arguments retain the normalized Scala identifiers. */
  def arguments(fields: List[Field]): String = fields.map(field => s"`${field.identifier}`").mkString(", ")

  /** Named required arguments exercise generated constructor defaults for all optional fields. */
  def minimalArguments(fields: List[Field]): String =
    fields.filter(!_.optional).map(field => s"`${field.identifier}` = ${field.placeholder}").mkString(", ")
