package com.worxbend.obs.websocket.client.protocol.models

import com.worxbend.obs.websocket.client.protocol.*

/** Validated view of an OBS InputVolumeMeter object. `raw` retains every field, including unknown additions. */
final class InputVolumeMeter private (
  val raw:            JsonObject,
  val inputName:      String,
  val inputLevelsMul: Vector[Vector[BigDecimal]],
  val inputUuid:      Field[String],
) extends ObjectModel

object InputVolumeMeter:
  val codec: ValueCodec[InputVolumeMeter] = ObjectModel.codec(read = decode)

  def decode(data: JsonObject): Either[ProtocolError, InputVolumeMeter] =
    for
      inputName      <- data.required(name = "inputName", codec = ValueCodec.string)
      inputLevelsMul <- data.required(
                          name  = "inputLevelsMul",
                          codec = ValueCodec.array(element = ValueCodec.array(element = ValueCodec.number)),
                        )
      inputUuid <- data.field(name = "inputUuid", codec = ValueCodec.string, nullable = true)
    yield new InputVolumeMeter(
      raw            = data,
      inputName      = inputName,
      inputLevelsMul = inputLevelsMul,
      inputUuid      = inputUuid,
    )
