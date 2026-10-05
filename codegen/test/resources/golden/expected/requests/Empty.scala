// Generated from the pinned OBS schema (sha256: fbbeded637bee2326c3f29f045480d8622adab5c4872d13c056f73513fdde881). Do not edit.
package com.worxbend.obs.websocket.client.protocol.requests

import com.worxbend.obs.websocket.client.protocol.*

/** Generated binding for Empty.
  *
  * Upstream: [[https://example.com/obs/blob/fixture/docs/generated/protocol.md#empty Empty]].
  */
final case class Empty() extends Request[EmptyResponse]:
  def requestType: String = "Empty"
  def requestData: JsonObject = JsonObject.empty
  def decodeResponse(data: JsonObject): Either[ProtocolError, EmptyResponse] = EmptyResponse.decode(data)

/** JSON decoder for [[Empty]] requests.
  *
  * Upstream: [[https://example.com/obs/blob/fixture/docs/generated/protocol.md#empty Empty]]
  */
object Empty:
  // Baseline request with every optional field omitted; exercises the constructor defaults.
  private[protocol] def minimal: Empty = Empty()
  def decode(data: JsonObject): Either[ProtocolError, Empty] = 
    val _ = data // Empty payloads deliberately accept unknown future fields.
    Right(Empty())

/** Response payload for [[Empty]].
  *
  * Upstream: [[https://example.com/obs/blob/fixture/docs/generated/protocol.md#empty EmptyResponse]]
  */
final case class EmptyResponse():
  def toJson: JsonObject = JsonObject.empty

/** JSON decoder for [[EmptyResponse]].
  *
  * Upstream: [[https://example.com/obs/blob/fixture/docs/generated/protocol.md#empty EmptyResponse]]
  */
object EmptyResponse:
  def decode(data: JsonObject): Either[ProtocolError, EmptyResponse] = 
    val _ = data // Empty payloads deliberately accept unknown future fields.
    Right(EmptyResponse())
