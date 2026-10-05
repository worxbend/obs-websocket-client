package com.worxbend.obs.websocket.client.protocol

trait Request[A]:
  def requestType: String
  def requestData: JsonObject
  def decodeResponse(data: JsonObject): Either[ProtocolError, A]
  final def validate: Either[ProtocolError, Unit] =
    Catalog.decodeRequest(name = requestType, data = requestData).map(_ => ())

/** Explicit escape hatch for extensions and requests added by newer peers. Bypasses the session capability check in
  * `request` and `batch`; the catalog decoder also falls back to this representation for unknown request types.
  */
final case class RawRequest(requestType: String, requestData: JsonObject = JsonObject.empty)
    extends Request[JsonObject]:
  def decodeResponse(data: JsonObject): Either[ProtocolError, JsonObject] = Right(data)
