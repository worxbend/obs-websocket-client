package com.worxbend.obs.websocket.client.protocol.models

import com.worxbend.obs.websocket.client.protocol.*

/** Validated view of an OBS Output object. `raw` retains every field, including unknown additions. */
final class Output private (
    val raw: JsonObject,
    val outputName: String,
    val outputKind: String,
    val outputWidth: BigDecimal,
    val outputHeight: BigDecimal,
    val outputActive: Boolean,
    val outputFlags: OutputFlags
) extends ObjectModel

object Output:
  val codec: ValueCodec[Output] = ObjectModel.codec(decode)

  def decode(data: JsonObject): Either[ProtocolError, Output] =
    for
      outputName <- data.required("outputName", ValueCodec.string)
      outputKind <- data.required("outputKind", ValueCodec.string)
      outputWidth <- data.required("outputWidth", ValueCodec.number)
      outputHeight <- data.required("outputHeight", ValueCodec.number)
      outputActive <- data.required("outputActive", ValueCodec.boolean)
      outputFlags <- data.required("outputFlags", OutputFlags.codec)
    yield new Output(data, outputName, outputKind, outputWidth, outputHeight, outputActive, outputFlags)
