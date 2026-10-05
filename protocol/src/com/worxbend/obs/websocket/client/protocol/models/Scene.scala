package com.worxbend.obs.websocket.client.protocol.models

import com.worxbend.obs.websocket.client.protocol.*

/** Validated view of an OBS Scene object. `raw` retains every field, including unknown additions. */
final class Scene private (
  val raw:        JsonObject,
  val sceneName:  String,
  val sceneIndex: BigDecimal,
  val sceneUuid:  Field[String],
) extends ObjectModel

object Scene:
  val codec: ValueCodec[Scene] = ObjectModel.codec(read = decode)

  def decode(data: JsonObject): Either[ProtocolError, Scene] =
    for
      sceneName  <- data.required(name = "sceneName", codec = ValueCodec.string)
      sceneIndex <- data.required(name = "sceneIndex", codec = ValueCodec.number)
      sceneUuid  <- data.field(name = "sceneUuid", codec = ValueCodec.string, nullable = true)
    yield new Scene(raw = data, sceneName = sceneName, sceneIndex = sceneIndex, sceneUuid = sceneUuid)
