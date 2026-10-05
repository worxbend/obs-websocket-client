package com.worxbend.obs.websocket.client.protocol.models

import com.worxbend.obs.websocket.client.protocol.*

/** Validated view of an OBS Monitor object. `raw` retains every field, including unknown additions. */
final class Monitor private (
  val raw:              JsonObject,
  val monitorHeight:    BigDecimal,
  val monitorIndex:     BigDecimal,
  val monitorPositionX: BigDecimal,
  val monitorPositionY: BigDecimal,
  val monitorWidth:     BigDecimal,
  val monitorName:      String,
) extends ObjectModel

object Monitor:
  val codec: ValueCodec[Monitor] = ObjectModel.codec(read = decode)

  def decode(data: JsonObject): Either[ProtocolError, Monitor] =
    for
      monitorHeight    <- data.required(name = "monitorHeight", codec = ValueCodec.number)
      monitorIndex     <- data.required(name = "monitorIndex", codec = ValueCodec.number)
      monitorPositionX <- data.required(name = "monitorPositionX", codec = ValueCodec.number)
      monitorPositionY <- data.required(name = "monitorPositionY", codec = ValueCodec.number)
      monitorWidth     <- data.required(name = "monitorWidth", codec = ValueCodec.number)
      monitorName      <- data.required(name = "monitorName", codec = ValueCodec.string)
    yield new Monitor(
      raw              = data,
      monitorHeight    = monitorHeight,
      monitorIndex     = monitorIndex,
      monitorPositionX = monitorPositionX,
      monitorPositionY = monitorPositionY,
      monitorWidth     = monitorWidth,
      monitorName      = monitorName,
    )
