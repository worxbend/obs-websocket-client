package com.worxbend.obs.websocket.client.protocol.models

import com.worxbend.obs.websocket.client.protocol.*

/** Validated view of an OBS Canvas object. `raw` retains every field, including unknown additions. */
final class Canvas private (
    val raw: JsonObject,
    val canvasName: String,
    val canvasUuid: String,
    val canvasWidth: Field[BigDecimal],
    val canvasHeight: Field[BigDecimal]
) extends ObjectModel

object Canvas:
  val codec: ValueCodec[Canvas] = ObjectModel.codec(decode)

  def decode(data: JsonObject): Either[ProtocolError, Canvas] =
    for
      canvasName <- data.required("canvasName", ValueCodec.string)
      canvasUuid <- data.required("canvasUuid", ValueCodec.string)
      canvasWidth <- data.field("canvasWidth", ValueCodec.number, nullable = true)
      canvasHeight <- data.field("canvasHeight", ValueCodec.number, nullable = true)
    yield new Canvas(data, canvasName, canvasUuid, canvasWidth, canvasHeight)
