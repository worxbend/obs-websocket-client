package com.worxbend.obs.websocket.client.protocol.models

import com.worxbend.obs.websocket.client.protocol.*

/** Validated view of an OBS Transition object. `raw` retains every field, including unknown additions. */
final class Transition private (
    val raw: JsonObject,
    val transitionName: String,
    val transitionKind: String,
    val transitionConfigurable: Boolean,
    val transitionFixed: Boolean,
    val transitionUuid: Field[String]
) extends ObjectModel

object Transition:
  val codec: ValueCodec[Transition] = ObjectModel.codec(decode)

  def decode(data: JsonObject): Either[ProtocolError, Transition] =
    for
      transitionName <- data.required("transitionName", ValueCodec.string)
      transitionKind <- data.required("transitionKind", ValueCodec.string)
      transitionConfigurable <- data.required("transitionConfigurable", ValueCodec.boolean)
      transitionFixed <- data.required("transitionFixed", ValueCodec.boolean)
      transitionUuid <- data.field("transitionUuid", ValueCodec.string, nullable = true)
    yield new Transition(data, transitionName, transitionKind, transitionConfigurable, transitionFixed, transitionUuid)
