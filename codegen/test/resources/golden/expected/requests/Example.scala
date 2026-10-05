// Generated from the pinned OBS schema (sha256: fbbeded637bee2326c3f29f045480d8622adab5c4872d13c056f73513fdde881). Do not edit.
package com.worxbend.obs.websocket.client.protocol.requests

import com.worxbend.obs.websocket.client.protocol.*

/** Example request.
  *
  * Upstream: [[https://example.com/obs/blob/fixture/docs/generated/protocol.md#example Example]] (initial version 5.0.0, RPC version 1). Deprecated upstream.
  * @param `payloadRequestType` Quoted * / docs
  * @param `parent.child` 
  * @param `slot` 
  */
final case class Example(`payloadRequestType`: String, `parent.child`: Field[Boolean] = Field.Missing, `slot`: Option[JsonObject]) extends Request[ExampleResponse]:
  def requestType: String = "Example"
  def requestData: JsonObject = NestedFields.encode(Map("requestType" -> ValueCodec.string.encode(`payloadRequestType`)) ++ ValueCodec.put("parent.child", `parent.child`, ValueCodec.boolean) ++ Map("slot" -> ValueCodec.nullable(ValueCodec.obj).encode(`slot`)))
  def decodeResponse(data: JsonObject): Either[ProtocolError, ExampleResponse] = ExampleResponse.decode(data)

/** JSON decoder for [[Example]] requests.
  *
  * Upstream: [[https://example.com/obs/blob/fixture/docs/generated/protocol.md#example Example]]
  */
object Example:
  // Baseline request with every optional field omitted; exercises the constructor defaults.
  private[protocol] def minimal: Example = Example(`payloadRequestType` = "", `slot` = None)
  def decode(data: JsonObject): Either[ProtocolError, Example] = 
    for
      `payloadRequestType` <- data.required("requestType", ValueCodec.string)
      `parent.child` <- NestedFields.field(data, "parent.child", ValueCodec.boolean, false)
      `slot` <- data.required("slot", ValueCodec.nullable(ValueCodec.obj))
    yield Example(`payloadRequestType`, `parent.child`, `slot`)

/** Response payload for [[Example]].
  *
  * Upstream: [[https://example.com/obs/blob/fixture/docs/generated/protocol.md#example ExampleResponse]]
  * @param `result` 
  */
final case class ExampleResponse(`result`: BigDecimal):
  def toJson: JsonObject = JsonObject(Map("result" -> ValueCodec.number.encode(`result`)))

/** JSON decoder for [[ExampleResponse]].
  *
  * Upstream: [[https://example.com/obs/blob/fixture/docs/generated/protocol.md#example ExampleResponse]]
  */
object ExampleResponse:
  def decode(data: JsonObject): Either[ProtocolError, ExampleResponse] = 
    for
      `result` <- data.required("result", ValueCodec.number)
    yield ExampleResponse(`result`)
