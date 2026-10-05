package com.worxbend.obs.websocket.client.protocol.models

import com.worxbend.obs.websocket.client.protocol.*

/** Validated view of an OBS Output object. `raw` retains every field, including unknown additions. */
final class Output private (
  val raw:          JsonObject,
  val outputName:   String,
  val outputKind:   String,
  val outputWidth:  BigDecimal,
  val outputHeight: BigDecimal,
  val outputActive: Boolean,
  val outputFlags:  OutputFlags,
) extends ObjectModel

object Output:
  val codec: ValueCodec[Output] = ObjectModel.codec(read = decode)

  def decode(data: JsonObject): Either[ProtocolError, Output] =
    for
      outputName   <- data.required(name = "outputName", codec = ValueCodec.string)
      outputKind   <- data.required(name = "outputKind", codec = ValueCodec.string)
      outputWidth  <- data.required(name = "outputWidth", codec = ValueCodec.number)
      outputHeight <- data.required(name = "outputHeight", codec = ValueCodec.number)
      outputActive <- data.required(name = "outputActive", codec = ValueCodec.boolean)
      outputFlags  <- data.required(name = "outputFlags", codec = OutputFlags.codec)
    yield new Output(
      raw          = data,
      outputName   = outputName,
      outputKind   = outputKind,
      outputWidth  = outputWidth,
      outputHeight = outputHeight,
      outputActive = outputActive,
      outputFlags  = outputFlags,
    )
