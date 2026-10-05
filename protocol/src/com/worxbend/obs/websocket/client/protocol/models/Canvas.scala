package com.worxbend.obs.websocket.client.protocol.models

import com.worxbend.obs.websocket.client.protocol.*

/** Validated view of an OBS Canvas object. `raw` retains every field, including unknown additions. */
final class Canvas private (
  val raw:          JsonObject,
  val canvasName:   String,
  val canvasUuid:   String,
  val canvasWidth:  Field[BigDecimal],
  val canvasHeight: Field[BigDecimal],
) extends ObjectModel

object Canvas:
  val codec: ValueCodec[Canvas] = ObjectModel.codec(read = decode)

  def decode(data: JsonObject): Either[ProtocolError, Canvas] =
    for
      canvasName   <- data.required(name = "canvasName", codec = ValueCodec.string)
      canvasUuid   <- data.required(name = "canvasUuid", codec = ValueCodec.string)
      canvasWidth  <- data.field(name = "canvasWidth", codec = ValueCodec.number, nullable = true)
      canvasHeight <- data.field(name = "canvasHeight", codec = ValueCodec.number, nullable = true)
    yield new Canvas(
      raw          = data,
      canvasName   = canvasName,
      canvasUuid   = canvasUuid,
      canvasWidth  = canvasWidth,
      canvasHeight = canvasHeight,
    )
