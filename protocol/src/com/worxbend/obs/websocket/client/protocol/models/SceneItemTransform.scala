package com.worxbend.obs.websocket.client.protocol.models

import com.worxbend.obs.websocket.client.protocol.*

/** Validated view of an OBS SceneItemTransform object. `raw` retains every field, including unknown additions. */
final class SceneItemTransform private (
    val raw: JsonObject,
    val alignment: BigDecimal,
    val boundsAlignment: BigDecimal,
    val boundsHeight: BigDecimal,
    val boundsWidth: BigDecimal,
    val cropBottom: BigDecimal,
    val cropLeft: BigDecimal,
    val cropRight: BigDecimal,
    val cropTop: BigDecimal,
    val height: BigDecimal,
    val positionX: BigDecimal,
    val positionY: BigDecimal,
    val rotation: BigDecimal,
    val scaleX: BigDecimal,
    val scaleY: BigDecimal,
    val sourceHeight: BigDecimal,
    val sourceWidth: BigDecimal,
    val width: BigDecimal,
    val boundsType: String,
    val cropToBounds: Field[Boolean]
) extends ObjectModel

object SceneItemTransform:
  val codec: ValueCodec[SceneItemTransform] = ObjectModel.codec(decode)

  def decode(data: JsonObject): Either[ProtocolError, SceneItemTransform] =
    for
      alignment <- data.required("alignment", ValueCodec.number)
      boundsAlignment <- data.required("boundsAlignment", ValueCodec.number)
      boundsHeight <- data.required("boundsHeight", ValueCodec.number)
      boundsWidth <- data.required("boundsWidth", ValueCodec.number)
      cropBottom <- data.required("cropBottom", ValueCodec.number)
      cropLeft <- data.required("cropLeft", ValueCodec.number)
      cropRight <- data.required("cropRight", ValueCodec.number)
      cropTop <- data.required("cropTop", ValueCodec.number)
      height <- data.required("height", ValueCodec.number)
      positionX <- data.required("positionX", ValueCodec.number)
      positionY <- data.required("positionY", ValueCodec.number)
      rotation <- data.required("rotation", ValueCodec.number)
      scaleX <- data.required("scaleX", ValueCodec.number)
      scaleY <- data.required("scaleY", ValueCodec.number)
      sourceHeight <- data.required("sourceHeight", ValueCodec.number)
      sourceWidth <- data.required("sourceWidth", ValueCodec.number)
      width <- data.required("width", ValueCodec.number)
      boundsType <- data.required("boundsType", ValueCodec.string)
      cropToBounds <- data.field("cropToBounds", ValueCodec.boolean, nullable = true)
    yield new SceneItemTransform(
      data,
      alignment,
      boundsAlignment,
      boundsHeight,
      boundsWidth,
      cropBottom,
      cropLeft,
      cropRight,
      cropTop,
      height,
      positionX,
      positionY,
      rotation,
      scaleX,
      scaleY,
      sourceHeight,
      sourceWidth,
      width,
      boundsType,
      cropToBounds
    )
