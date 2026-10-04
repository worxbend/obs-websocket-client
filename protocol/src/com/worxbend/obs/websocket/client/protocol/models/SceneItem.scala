package com.worxbend.obs.websocket.client.protocol.models

import com.worxbend.obs.websocket.client.protocol.*

/** Validated view of an OBS SceneItem object. `raw` retains every field, including unknown additions. */
final class SceneItem private (
    val raw: JsonObject,
    val sceneItemId: BigDecimal,
    val sceneItemIndex: BigDecimal,
    val sourceName: String,
    val sourceType: String,
    val sceneItemEnabled: Boolean,
    val sceneItemLocked: Boolean,
    val sceneItemBlendMode: String,
    val sceneItemTransform: SceneItemTransform,
    val sourceUuid: Field[String],
    val inputKind: Field[String],
    val isGroup: Field[Boolean]
) extends ObjectModel

object SceneItem:
  val codec: ValueCodec[SceneItem] = ObjectModel.codec(decode)

  def decode(data: JsonObject): Either[ProtocolError, SceneItem] =
    for
      sceneItemId <- data.required("sceneItemId", ValueCodec.number)
      sceneItemIndex <- data.required("sceneItemIndex", ValueCodec.number)
      sourceName <- data.required("sourceName", ValueCodec.string)
      sourceType <- data.required("sourceType", ValueCodec.string)
      sceneItemEnabled <- data.required("sceneItemEnabled", ValueCodec.boolean)
      sceneItemLocked <- data.required("sceneItemLocked", ValueCodec.boolean)
      sceneItemBlendMode <- data.required("sceneItemBlendMode", ValueCodec.string)
      sceneItemTransform <- data.required("sceneItemTransform", SceneItemTransform.codec)
      sourceUuid <- data.field("sourceUuid", ValueCodec.string, nullable = true)
      inputKind <- data.field("inputKind", ValueCodec.string, nullable = true)
      isGroup <- data.field("isGroup", ValueCodec.boolean, nullable = true)
    yield new SceneItem(
      data,
      sceneItemId,
      sceneItemIndex,
      sourceName,
      sourceType,
      sceneItemEnabled,
      sceneItemLocked,
      sceneItemBlendMode,
      sceneItemTransform,
      sourceUuid,
      inputKind,
      isGroup
    )
