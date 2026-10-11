package com.worxbend.obs.websocket.client

import com.worxbend.obs.websocket.client.protocol.*

/** Immutable catalog-validated snapshot. No extensible getter is evaluated by admission or batch decoding. */
final private[client] case class PreparedRequest(requestType: String, data: JsonObject):
  def batchEntry: JsonObject = JsonObject(fields =
    Map(
      "requestType" -> JsonValue.Str(value = requestType),
      "requestData" -> data,
    )
  )

private[client] object PreparedRequest:
  def from(request: Request[?], available: Set[String]): Either[ObsError, PreparedRequest] =
    val name = request.requestType
    if !request.isInstanceOf[RawRequest] && !available.contains(name) then
      Left(ObsError.UnsupportedRequest(requestType = name))
    else
      val data = request.requestData
      Catalog.decodeRequest(name = name, data = data).left.map(SessionWire.invalid)
        .map(_ => PreparedRequest(requestType = name, data = data))
