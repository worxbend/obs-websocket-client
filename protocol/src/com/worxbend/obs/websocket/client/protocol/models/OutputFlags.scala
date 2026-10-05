package com.worxbend.obs.websocket.client.protocol.models

import com.worxbend.obs.websocket.client.protocol.*

/** Validated view of an OBS OutputFlags object. `raw` retains every field, including unknown additions. */
final class OutputFlags private (
  val raw:                    JsonObject,
  val OBS_OUTPUT_AUDIO:       Boolean,
  val OBS_OUTPUT_VIDEO:       Boolean,
  val OBS_OUTPUT_ENCODED:     Boolean,
  val OBS_OUTPUT_MULTI_TRACK: Boolean,
  val OBS_OUTPUT_SERVICE:     Boolean,
) extends ObjectModel

object OutputFlags:
  val codec: ValueCodec[OutputFlags] = ObjectModel.codec(read = decode)

  def decode(data: JsonObject): Either[ProtocolError, OutputFlags] =
    for
      OBS_OUTPUT_AUDIO       <- data.required(name = "OBS_OUTPUT_AUDIO", codec = ValueCodec.boolean)
      OBS_OUTPUT_VIDEO       <- data.required(name = "OBS_OUTPUT_VIDEO", codec = ValueCodec.boolean)
      OBS_OUTPUT_ENCODED     <- data.required(name = "OBS_OUTPUT_ENCODED", codec = ValueCodec.boolean)
      OBS_OUTPUT_MULTI_TRACK <- data.required(name = "OBS_OUTPUT_MULTI_TRACK", codec = ValueCodec.boolean)
      OBS_OUTPUT_SERVICE     <- data.required(name = "OBS_OUTPUT_SERVICE", codec = ValueCodec.boolean)
    yield new OutputFlags(
      raw                    = data,
      OBS_OUTPUT_AUDIO       = OBS_OUTPUT_AUDIO,
      OBS_OUTPUT_VIDEO       = OBS_OUTPUT_VIDEO,
      OBS_OUTPUT_ENCODED     = OBS_OUTPUT_ENCODED,
      OBS_OUTPUT_MULTI_TRACK = OBS_OUTPUT_MULTI_TRACK,
      OBS_OUTPUT_SERVICE     = OBS_OUTPUT_SERVICE,
    )
