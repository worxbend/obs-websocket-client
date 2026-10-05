package com.worxbend.obs.websocket.client.codegen.templates

import com.worxbend.obs.websocket.client.codegen.model.*
import com.worxbend.obs.websocket.client.codegen.schema.Provenance
import com.worxbend.obs.websocket.client.codegen.ScalaLiteral.quote
import FieldFragments.*
import SourceFragments.*

/** Source layout for one request, its response, and their codecs. No raw schema parsing occurs here.
  *
  * Produces `requests/<name>.scala` in the protocol requests package. The request and response share one file;
  * companion decoders and a minimal request fixture use the same normalized fields as the constructor and encoder. The
  * typed Context is assembled by CodeGenerator; render returns complete text and performs no file writes.
  */
private[codegen] object RequestTemplate:
  /** Typed inputs for a complete request source file. */
  final case class Context(request: RequestDefinition, provenance: Provenance)

  /** Renders a newline-terminated Scala file from an already-normalized request. */
  def render(context: Context): String =
    val request        = context.request
    val name           = request.name
    val fields         = request.requestFields
    val responseFields = request.responseFields
    val provenance     = context.provenance
    val requestDoc = documentation(doc = request.documentation, name = name, provenance = provenance, fields = fields)
    val decoderDoc = seeDoc(
      summary      = s"JSON decoder for [[$name]] requests.",
      name         = name,
      anchorTarget = name,
      provenance   = provenance,
    )
    val responseDoc = seeDoc(
      summary      = s"Response payload for [[$name]].",
      name         = s"${name}Response",
      anchorTarget = name,
      provenance   = provenance,
      fields       = responseFields,
    )
    val responseDecoderDoc = seeDoc(
      summary      = s"JSON decoder for [[${name}Response]].",
      name         = s"${name}Response",
      anchorTarget = name,
      provenance   = provenance,
    )
    val fileHeader         = header(pkg = s"$base.requests", provenance = provenance, withImport = true)
    val requestParameters  = parameters(fields = fields)
    val responseParameters = parameters(fields = responseFields)
    val requestType        = quote(value = name)
    val requestEncoder     = encode(fields = fields)
    val responseEncoder    = encode(fields = responseFields)
    val requiredArguments  = minimalArguments(fields = fields)
    val requestDecoder     = decode(name = name, fields = fields)
    val responseDecoder    = decode(name = s"${name}Response", fields = responseFields)
    s"""$fileHeader${requestDoc}final case class $name($requestParameters) extends Request[${name}Response]:
       |  def requestType: String = $requestType
       |  def requestData: JsonObject = $requestEncoder
       |  def decodeResponse(data: JsonObject): Either[ProtocolError, ${name}Response] = ${name}Response.decode(data)
       |
       |${decoderDoc}object $name:
       |  // Baseline request with every optional field omitted; exercises the constructor defaults.
       |  private[protocol] def minimal: $name = $name($requiredArguments)
       |  def decode(data: JsonObject): Either[ProtocolError, $name] = $requestDecoder
       |
       |${responseDoc}final case class ${name}Response($responseParameters):
       |  def toJson: JsonObject = $responseEncoder
       |
       |${responseDecoderDoc}object ${name}Response:
       |  def decode(data: JsonObject): Either[ProtocolError, ${name}Response] = $responseDecoder
       |""".stripMargin
