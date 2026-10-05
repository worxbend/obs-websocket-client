package com.worxbend.obs.websocket.client.protocol.models

import com.worxbend.obs.websocket.client.protocol.*

/** Validated view of an OBS Input object. `raw` retains every field, including unknown additions. */
final class Input private (
  val raw:                  JsonObject,
  val inputName:            String,
  val inputKind:            String,
  val unversionedInputKind: String,
  val inputUuid:            Field[String],
  val inputKindCaps:        Field[BigDecimal],
) extends ObjectModel

object Input:
  val codec: ValueCodec[Input] = ObjectModel.codec(read = decode)

  def decode(data: JsonObject): Either[ProtocolError, Input] =
    for
      inputName            <- data.required(name = "inputName", codec = ValueCodec.string)
      inputKind            <- data.required(name = "inputKind", codec = ValueCodec.string)
      unversionedInputKind <- data.required(name = "unversionedInputKind", codec = ValueCodec.string)
      inputUuid            <- data.field(name = "inputUuid", codec = ValueCodec.string, nullable = true)
      inputKindCaps        <- data.field(name = "inputKindCaps", codec = ValueCodec.number, nullable = true)
    yield new Input(
      raw                  = data,
      inputName            = inputName,
      inputKind            = inputKind,
      unversionedInputKind = unversionedInputKind,
      inputUuid            = inputUuid,
      inputKindCaps        = inputKindCaps,
    )
