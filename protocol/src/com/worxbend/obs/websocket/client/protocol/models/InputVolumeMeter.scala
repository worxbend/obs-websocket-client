package com.worxbend.obs.websocket.client.protocol.models

import com.worxbend.obs.websocket.client.protocol.*

/** Validated view of an OBS InputVolumeMeter object. `raw` retains every field, including unknown additions. */
final class InputVolumeMeter private (
    val raw: JsonObject,
    val inputName: String,
    val inputLevelsMul: Vector[Vector[BigDecimal]],
    val inputUuid: Field[String]
) extends ObjectModel

object InputVolumeMeter:
  val codec: ValueCodec[InputVolumeMeter] = ObjectModel.codec(decode)

  def decode(data: JsonObject): Either[ProtocolError, InputVolumeMeter] =
    for
      inputName <- data.required("inputName", ValueCodec.string)
      inputLevelsMul <- data.required("inputLevelsMul", ValueCodec.array(ValueCodec.array(ValueCodec.number)))
      inputUuid <- data.field("inputUuid", ValueCodec.string, nullable = true)
    yield new InputVolumeMeter(data, inputName, inputLevelsMul, inputUuid)
