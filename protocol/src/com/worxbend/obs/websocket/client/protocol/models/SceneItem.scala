package com.worxbend.obs.websocket.client.protocol.models

import com.worxbend.obs.websocket.client.protocol.*

/** Validated view of an OBS SceneItem object. `raw` retains every field, including unknown additions. */
final class SceneItem private (
  val raw:                JsonObject,
  val sceneItemId:        BigDecimal,
  val sceneItemIndex:     BigDecimal,
  val sourceName:         String,
  val sourceType:         String,
  val sceneItemEnabled:   Boolean,
  val sceneItemLocked:    Boolean,
  val sceneItemBlendMode: String,
  val sceneItemTransform: SceneItemTransform,
  val sourceUuid:         Field[String],
  val inputKind:          Field[String],
  val isGroup:            Field[Boolean],
) extends ObjectModel

object SceneItem:
  val codec: ValueCodec[SceneItem] = ObjectModel.codec(read = decode)

  def decode(data: JsonObject): Either[ProtocolError, SceneItem] =
    for
      sceneItemId        <- data.required(name = "sceneItemId", codec = ValueCodec.number)
      sceneItemIndex     <- data.required(name = "sceneItemIndex", codec = ValueCodec.number)
      sourceName         <- data.required(name = "sourceName", codec = ValueCodec.string)
      sourceType         <- data.required(name = "sourceType", codec = ValueCodec.string)
      sceneItemEnabled   <- data.required(name = "sceneItemEnabled", codec = ValueCodec.boolean)
      sceneItemLocked    <- data.required(name = "sceneItemLocked", codec = ValueCodec.boolean)
      sceneItemBlendMode <- data.required(name = "sceneItemBlendMode", codec = ValueCodec.string)
      sceneItemTransform <- data.required(name = "sceneItemTransform", codec = SceneItemTransform.codec)
      sourceUuid         <- data.field(name = "sourceUuid", codec = ValueCodec.string, nullable = true)
      inputKind          <- data.field(name = "inputKind", codec = ValueCodec.string, nullable = true)
      isGroup            <- data.field(name = "isGroup", codec = ValueCodec.boolean, nullable = true)
    yield new SceneItem(
      raw                = data,
      sceneItemId        = sceneItemId,
      sceneItemIndex     = sceneItemIndex,
      sourceName         = sourceName,
      sourceType         = sourceType,
      sceneItemEnabled   = sceneItemEnabled,
      sceneItemLocked    = sceneItemLocked,
      sceneItemBlendMode = sceneItemBlendMode,
      sceneItemTransform = sceneItemTransform,
      sourceUuid         = sourceUuid,
      inputKind          = inputKind,
      isGroup            = isGroup,
    )
