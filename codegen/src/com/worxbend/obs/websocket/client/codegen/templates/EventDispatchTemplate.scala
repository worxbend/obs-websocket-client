package com.worxbend.obs.websocket.client.codegen.templates

import com.worxbend.obs.websocket.client.codegen.schema.Provenance
import com.worxbend.obs.websocket.client.codegen.ScalaLiteral.quote
import SourceFragments.*

/** Source layout for the event envelope and unknown-event fallback.
  *
  * Produces the shared `Event.scala` file in the protocol package. Known event names delegate to their generated
  * payload decoders; unknown names preserve the event name and raw payload in UnknownEvent. Only names and provenance
  * are needed here because EventTemplate owns each individual payload layout.
  */
private[codegen] object EventDispatchTemplate:
  /** Event names arrive in deterministic order from normalization. */
  final case class Context(names: List[String], provenance: Provenance)

  /** Renders the complete event dispatch file. */
  def render(context: Context): String =
    val cases = context.names.map(name => s"    case ${quote(name)} => events.$name.decode(data)").mkString("\n")
    val fileHeader = header(base, context.provenance, withImport = false)
    s"""$fileHeader/** Typed OBS event with its decoded payload. */
       |trait Event:
       |  def eventType: String
       |  def eventData: JsonObject
       |
       |/** Raw representation for events unknown to the pinned catalog. */
       |final case class UnknownEvent(eventType: String, eventData: JsonObject) extends Event
       |
       |/** Event envelope dispatch by `eventType`; unknown events decode to [[UnknownEvent]]. */
       |object Event:
       |  def decode(eventType: String, data: JsonObject): Either[ProtocolError, Event] = eventType match
       |$cases
       |    case _ => Right(UnknownEvent(eventType, data))
       |""".stripMargin
