package com.worxbend.obs.websocket.client.codegen.templates

import com.worxbend.obs.websocket.client.codegen.model.*
import com.worxbend.obs.websocket.client.codegen.schema.Provenance
import com.worxbend.obs.websocket.client.codegen.ScalaLiteral.quote
import FieldFragments.*
import SourceFragments.*

/** Source layout for one event, its typed selector, and its JSON decoder.
  *
  * Produces `events/<name>.scala` in the protocol events package. The payload implements the shared Event envelope; its
  * companion exposes a typed selector and decoder. EventDispatchTemplate separately renders the catalog-wide dispatch
  * table, so adding a schema event supplies both the payload and its dispatch entry.
  */
private[codegen] object EventTemplate:
  /** Typed inputs for a complete event source file. */
  final case class Context(event: EventDefinition, provenance: Provenance)

  /** Renders a newline-terminated Scala file from an already-normalized event. */
  def render(context: Context): String =
    val event = context.event
    val name = event.name
    val fields = event.fields
    val provenance = context.provenance
    val eventDoc = documentation(event.documentation, name, provenance, fields)
    val decoderDoc = seeDoc(s"JSON decoder for [[$name]] events.", name, name, provenance)
    val fileHeader = header(s"$base.events", provenance, withImport = true)
    val eventParameters = parameters(fields)
    val eventType = quote(name)
    val eventEncoder = encode(fields)
    val eventDecoder = decode(name, fields)
    s"""$fileHeader${eventDoc}final case class $name($eventParameters) extends Event:
       |  def eventType: String = $eventType
       |  def eventData: JsonObject = $eventEncoder
       |
       |${decoderDoc}object $name:
       |  val selector: EventSelector[$name] = EventSelector($eventType):
       |    case event: $name => Some(event)
       |    case _ => None
       |  def decode(data: JsonObject): Either[ProtocolError, $name] = $eventDecoder
       |""".stripMargin
