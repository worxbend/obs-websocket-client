package com.worxbend.obs.websocket.client.codegen.templates

import com.worxbend.obs.websocket.client.codegen.schema.Provenance
import com.worxbend.obs.websocket.client.codegen.ScalaLiteral.quote
import SourceFragments.*

/** Source layout for request discovery and chunked typed decoder tables.
  *
  * Produces the shared `Catalog.scala` file: known request names, minimal requests for catalog validation, typed
  * request dispatch with a RawRequest fallback, and internal response round-trip dispatch. Decoder helpers are split
  * into bounded groups to keep the full upstream catalog below JVM method bytecode limits.
  */
private[codegen] object CatalogTemplate:
  /** Request names arrive in deterministic order from normalization. */
  final case class Context(names: List[String], provenance: Provenance)

  /** Renders public request decoding and package-private response round-trip support. */
  def render(context: Context): String =
    val names           = context.names
    val requestNames    = names.map(quote).mkString(", ")
    val minimalRequests = names.map(name => s"requests.$name.minimal").mkString(", ")
    val requests        = dispatch(
      method   = "decodeRequest",
      result   = "Request[?]",
      names    = names,
      fallback = "Right(RawRequest(name, data))",
      name => s"requests.$name.decode(data)",
    )
    // The response round-trip table is an internal catalog-validation aid, not published API.
    val responses = dispatch(
      method   = "roundTripResponse",
      result   = "JsonObject",
      names    = names,
      fallback = "Right(data)",
      name => s"requests.${name}Response.decode(data).map(_.toJson)",
      visibility = "private[protocol] ",
    )
    val fileHeader = header(pkg = base, provenance = context.provenance, withImport = false)
    s"""$fileHeader/** Generated catalog of the pinned request types with typed decoders and raw fallbacks. */
       |object Catalog:
       |  val requestNames: Vector[String] = Vector($requestNames)
       |  private[protocol] val minimalRequests: Vector[Request[?]] = Vector($minimalRequests)
       |$requests$responses""".stripMargin

  /** Groups of 24 keep generated method bytecode below JVM class-file limits. Each helper ends with a newline. */
  private def dispatch(
    method:     String,
    result:     String,
    names:      List[String],
    fallback:   String,
    call:       String => String,
    visibility: String = "",
  ): String =
    val groups      = names.grouped(24).toList.zipWithIndex
    val decoderType = s"Map[String, JsonObject => Either[ProtocolError, $result]]"
    val tables  = if groups.isEmpty then "Map.empty" else groups.map((_, index) => s"$method$index").mkString(" ++ ")
    val helpers = groups.map: (group, index) =>
      val entries =
        group.map(name => s"    ${quote(value = name)} -> ((data: JsonObject) => ${call(name)})").mkString(",\n")
      s"""  private def $method$index: $decoderType = Map(
         |$entries
         |  )
         |""".stripMargin
    s"""  private val ${method}Decoders: $decoderType = $tables
       |  ${visibility}def $method(name: String, data: JsonObject): Either[ProtocolError, $result] =
       |    ${method}Decoders.get(name).fold[Either[ProtocolError, $result]]($fallback)(_(data))
       |${helpers.mkString("\n")}""".stripMargin
