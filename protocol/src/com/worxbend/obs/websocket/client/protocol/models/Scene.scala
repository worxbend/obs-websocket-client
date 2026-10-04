package com.worxbend.obs.websocket.client.protocol.models

import com.worxbend.obs.websocket.client.protocol.*

/** Validated view of an OBS Scene object. `raw` retains every field, including unknown additions. */
final class Scene private (
    val raw: JsonObject,
    val sceneName: String,
    val sceneIndex: BigDecimal,
    val sceneUuid: Field[String]
) extends ObjectModel

object Scene:
  val codec: ValueCodec[Scene] = ObjectModel.codec(decode)

  def decode(data: JsonObject): Either[ProtocolError, Scene] =
    for
      sceneName <- data.required("sceneName", ValueCodec.string)
      sceneIndex <- data.required("sceneIndex", ValueCodec.number)
      sceneUuid <- data.field("sceneUuid", ValueCodec.string, nullable = true)
    yield new Scene(data, sceneName, sceneIndex, sceneUuid)
