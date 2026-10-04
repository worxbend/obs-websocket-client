package com.worxbend.obs.websocket.client.protocol.models

import com.worxbend.obs.websocket.client.protocol.*

/** Validated view of an OBS OutputFlags object. `raw` retains every field, including unknown additions. */
final class OutputFlags private (
    val raw: JsonObject,
    val OBS_OUTPUT_AUDIO: Boolean,
    val OBS_OUTPUT_VIDEO: Boolean,
    val OBS_OUTPUT_ENCODED: Boolean,
    val OBS_OUTPUT_MULTI_TRACK: Boolean,
    val OBS_OUTPUT_SERVICE: Boolean
) extends ObjectModel

object OutputFlags:
  val codec: ValueCodec[OutputFlags] = ObjectModel.codec(decode)

  def decode(data: JsonObject): Either[ProtocolError, OutputFlags] =
    for
      OBS_OUTPUT_AUDIO <- data.required("OBS_OUTPUT_AUDIO", ValueCodec.boolean)
      OBS_OUTPUT_VIDEO <- data.required("OBS_OUTPUT_VIDEO", ValueCodec.boolean)
      OBS_OUTPUT_ENCODED <- data.required("OBS_OUTPUT_ENCODED", ValueCodec.boolean)
      OBS_OUTPUT_MULTI_TRACK <- data.required("OBS_OUTPUT_MULTI_TRACK", ValueCodec.boolean)
      OBS_OUTPUT_SERVICE <- data.required("OBS_OUTPUT_SERVICE", ValueCodec.boolean)
    yield new OutputFlags(
      data,
      OBS_OUTPUT_AUDIO,
      OBS_OUTPUT_VIDEO,
      OBS_OUTPUT_ENCODED,
      OBS_OUTPUT_MULTI_TRACK,
      OBS_OUTPUT_SERVICE
    )
