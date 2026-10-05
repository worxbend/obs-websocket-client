package com.worxbend.obs.websocket.client.protocol.models

import com.worxbend.obs.websocket.client.protocol.*

/** Validated view of an OBS Transition object. `raw` retains every field, including unknown additions. */
final class Transition private (
  val raw:                    JsonObject,
  val transitionName:         String,
  val transitionKind:         String,
  val transitionConfigurable: Boolean,
  val transitionFixed:        Boolean,
  val transitionUuid:         Field[String],
) extends ObjectModel

object Transition:
  val codec: ValueCodec[Transition] = ObjectModel.codec(read = decode)

  def decode(data: JsonObject): Either[ProtocolError, Transition] =
    for
      transitionName         <- data.required(name = "transitionName", codec = ValueCodec.string)
      transitionKind         <- data.required(name = "transitionKind", codec = ValueCodec.string)
      transitionConfigurable <- data.required(name = "transitionConfigurable", codec = ValueCodec.boolean)
      transitionFixed        <- data.required(name = "transitionFixed", codec = ValueCodec.boolean)
      transitionUuid         <- data.field(name = "transitionUuid", codec = ValueCodec.string, nullable = true)
    yield new Transition(
      raw                    = data,
      transitionName         = transitionName,
      transitionKind         = transitionKind,
      transitionConfigurable = transitionConfigurable,
      transitionFixed        = transitionFixed,
      transitionUuid         = transitionUuid,
    )
