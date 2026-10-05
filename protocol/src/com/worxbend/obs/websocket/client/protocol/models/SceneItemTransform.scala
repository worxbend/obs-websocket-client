package com.worxbend.obs.websocket.client.protocol.models

import com.worxbend.obs.websocket.client.protocol.*

/** Validated view of an OBS SceneItemTransform object. `raw` retains every field, including unknown additions. */
final class SceneItemTransform private (
  val raw:             JsonObject,
  val alignment:       BigDecimal,
  val boundsAlignment: BigDecimal,
  val boundsHeight:    BigDecimal,
  val boundsWidth:     BigDecimal,
  val cropBottom:      BigDecimal,
  val cropLeft:        BigDecimal,
  val cropRight:       BigDecimal,
  val cropTop:         BigDecimal,
  val height:          BigDecimal,
  val positionX:       BigDecimal,
  val positionY:       BigDecimal,
  val rotation:        BigDecimal,
  val scaleX:          BigDecimal,
  val scaleY:          BigDecimal,
  val sourceHeight:    BigDecimal,
  val sourceWidth:     BigDecimal,
  val width:           BigDecimal,
  val boundsType:      String,
  val cropToBounds:    Field[Boolean],
) extends ObjectModel

object SceneItemTransform:
  val codec: ValueCodec[SceneItemTransform] = ObjectModel.codec(read = decode)

  def decode(data: JsonObject): Either[ProtocolError, SceneItemTransform] =
    for
      alignment       <- data.required(name = "alignment", codec = ValueCodec.number)
      boundsAlignment <- data.required(name = "boundsAlignment", codec = ValueCodec.number)
      boundsHeight    <- data.required(name = "boundsHeight", codec = ValueCodec.number)
      boundsWidth     <- data.required(name = "boundsWidth", codec = ValueCodec.number)
      cropBottom      <- data.required(name = "cropBottom", codec = ValueCodec.number)
      cropLeft        <- data.required(name = "cropLeft", codec = ValueCodec.number)
      cropRight       <- data.required(name = "cropRight", codec = ValueCodec.number)
      cropTop         <- data.required(name = "cropTop", codec = ValueCodec.number)
      height          <- data.required(name = "height", codec = ValueCodec.number)
      positionX       <- data.required(name = "positionX", codec = ValueCodec.number)
      positionY       <- data.required(name = "positionY", codec = ValueCodec.number)
      rotation        <- data.required(name = "rotation", codec = ValueCodec.number)
      scaleX          <- data.required(name = "scaleX", codec = ValueCodec.number)
      scaleY          <- data.required(name = "scaleY", codec = ValueCodec.number)
      sourceHeight    <- data.required(name = "sourceHeight", codec = ValueCodec.number)
      sourceWidth     <- data.required(name = "sourceWidth", codec = ValueCodec.number)
      width           <- data.required(name = "width", codec = ValueCodec.number)
      boundsType      <- data.required(name = "boundsType", codec = ValueCodec.string)
      cropToBounds    <- data.field(name = "cropToBounds", codec = ValueCodec.boolean, nullable = true)
    yield new SceneItemTransform(
      raw             = data,
      alignment       = alignment,
      boundsAlignment = boundsAlignment,
      boundsHeight    = boundsHeight,
      boundsWidth     = boundsWidth,
      cropBottom      = cropBottom,
      cropLeft        = cropLeft,
      cropRight       = cropRight,
      cropTop         = cropTop,
      height          = height,
      positionX       = positionX,
      positionY       = positionY,
      rotation        = rotation,
      scaleX          = scaleX,
      scaleY          = scaleY,
      sourceHeight    = sourceHeight,
      sourceWidth     = sourceWidth,
      width           = width,
      boundsType      = boundsType,
      cropToBounds    = cropToBounds,
    )
