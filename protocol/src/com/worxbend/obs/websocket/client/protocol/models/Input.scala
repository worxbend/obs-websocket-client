package com.worxbend.obs.websocket.client.protocol.models

import com.worxbend.obs.websocket.client.protocol.*

/** Validated view of an OBS Input object. `raw` retains every field, including unknown additions. */
final class Input private (
    val raw: JsonObject,
    val inputName: String,
    val inputKind: String,
    val unversionedInputKind: String,
    val inputUuid: Field[String],
    val inputKindCaps: Field[BigDecimal]
) extends ObjectModel

object Input:
  val codec: ValueCodec[Input] = ObjectModel.codec(decode)

  def decode(data: JsonObject): Either[ProtocolError, Input] =
    for
      inputName <- data.required("inputName", ValueCodec.string)
      inputKind <- data.required("inputKind", ValueCodec.string)
      unversionedInputKind <- data.required("unversionedInputKind", ValueCodec.string)
      inputUuid <- data.field("inputUuid", ValueCodec.string, nullable = true)
      inputKindCaps <- data.field("inputKindCaps", ValueCodec.number, nullable = true)
    yield new Input(data, inputName, inputKind, unversionedInputKind, inputUuid, inputKindCaps)
