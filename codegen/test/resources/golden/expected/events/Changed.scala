// Generated from the pinned OBS schema (sha256: fbbeded637bee2326c3f29f045480d8622adab5c4872d13c056f73513fdde881). Do not edit.
package com.worxbend.obs.websocket.client.protocol.events

import com.worxbend.obs.websocket.client.protocol.*

/** Generated binding for Changed.
  *
  * Upstream: [[https://example.com/obs/blob/fixture/docs/generated/protocol.md#changed Changed]].
  * @param `payloadRequestType` Quoted * / docs
  * @param `parent.child` 
  * @param `slot` 
  */
final case class Changed(`payloadRequestType`: String, `parent.child`: Field[Boolean] = Field.Missing, `slot`: Option[JsonObject]) extends Event:
  def eventType: String = "Changed"
  def eventData: JsonObject = NestedFields.encode(Map("requestType" -> ValueCodec.string.encode(`payloadRequestType`)) ++ ValueCodec.put("parent.child", `parent.child`, ValueCodec.boolean) ++ Map("slot" -> ValueCodec.nullable(ValueCodec.obj).encode(`slot`)))

/** JSON decoder for [[Changed]] events.
  *
  * Upstream: [[https://example.com/obs/blob/fixture/docs/generated/protocol.md#changed Changed]]
  */
object Changed:
  val selector: EventSelector[Changed] = EventSelector("Changed"):
    case event: Changed => Some(event)
    case _ => None
  def decode(data: JsonObject): Either[ProtocolError, Changed] = 
    for
      `payloadRequestType` <- data.required("requestType", ValueCodec.string)
      `parent.child` <- NestedFields.field(data, "parent.child", ValueCodec.boolean, false)
      `slot` <- data.required("slot", ValueCodec.nullable(ValueCodec.obj))
    yield Changed(`payloadRequestType`, `parent.child`, `slot`)
