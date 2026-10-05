// Generated from the pinned OBS schema (sha256: fbbeded637bee2326c3f29f045480d8622adab5c4872d13c056f73513fdde881). Do not edit.
package com.worxbend.obs.websocket.client.protocol

/** Generated catalog of the pinned request types with typed decoders and raw fallbacks. */
object Catalog:
  val requestNames: Vector[String] = Vector("Empty", "Example")
  private[protocol] val minimalRequests: Vector[Request[?]] = Vector(requests.Empty.minimal, requests.Example.minimal)
  private val decodeRequestDecoders: Map[String, JsonObject => Either[ProtocolError, Request[?]]] = decodeRequest0
  def decodeRequest(name: String, data: JsonObject): Either[ProtocolError, Request[?]] =
    decodeRequestDecoders.get(name).fold[Either[ProtocolError, Request[?]]](Right(RawRequest(name, data)))(_(data))
  private def decodeRequest0: Map[String, JsonObject => Either[ProtocolError, Request[?]]] = Map(
    "Empty" -> ((data: JsonObject) => requests.Empty.decode(data)),
    "Example" -> ((data: JsonObject) => requests.Example.decode(data))
  )
  private val roundTripResponseDecoders: Map[String, JsonObject => Either[ProtocolError, JsonObject]] = roundTripResponse0
  private[protocol] def roundTripResponse(name: String, data: JsonObject): Either[ProtocolError, JsonObject] =
    roundTripResponseDecoders.get(name).fold[Either[ProtocolError, JsonObject]](Right(data))(_(data))
  private def roundTripResponse0: Map[String, JsonObject => Either[ProtocolError, JsonObject]] = Map(
    "Empty" -> ((data: JsonObject) => requests.EmptyResponse.decode(data).map(_.toJson)),
    "Example" -> ((data: JsonObject) => requests.ExampleResponse.decode(data).map(_.toJson))
  )
