// Generated from the pinned OBS schema (sha256: fbbeded637bee2326c3f29f045480d8622adab5c4872d13c056f73513fdde881). Do not edit.
package com.worxbend.obs.websocket.client.protocol

/** Typed OBS event with its decoded payload. */
trait Event:
  def eventType: String
  def eventData: JsonObject

/** Raw representation for events unknown to the pinned catalog. */
final case class UnknownEvent(eventType: String, eventData: JsonObject) extends Event

/** Event envelope dispatch by `eventType`; unknown events decode to [[UnknownEvent]]. */
object Event:
  def decode(eventType: String, data: JsonObject): Either[ProtocolError, Event] = eventType match
    case "Changed" => events.Changed.decode(data)
    case _ => Right(UnknownEvent(eventType, data))
