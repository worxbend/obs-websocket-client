package com.worxbend.obs.websocket.client.protocol.models

import com.worxbend.obs.websocket.client.protocol.*

/** Validated view of an OBS Monitor object. `raw` retains every field, including unknown additions. */
final class Monitor private (
    val raw: JsonObject,
    val monitorHeight: BigDecimal,
    val monitorIndex: BigDecimal,
    val monitorPositionX: BigDecimal,
    val monitorPositionY: BigDecimal,
    val monitorWidth: BigDecimal,
    val monitorName: String
) extends ObjectModel

object Monitor:
  val codec: ValueCodec[Monitor] = ObjectModel.codec(decode)

  def decode(data: JsonObject): Either[ProtocolError, Monitor] =
    for
      monitorHeight <- data.required("monitorHeight", ValueCodec.number)
      monitorIndex <- data.required("monitorIndex", ValueCodec.number)
      monitorPositionX <- data.required("monitorPositionX", ValueCodec.number)
      monitorPositionY <- data.required("monitorPositionY", ValueCodec.number)
      monitorWidth <- data.required("monitorWidth", ValueCodec.number)
      monitorName <- data.required("monitorName", ValueCodec.string)
    yield new Monitor(data, monitorHeight, monitorIndex, monitorPositionX, monitorPositionY, monitorWidth, monitorName)
