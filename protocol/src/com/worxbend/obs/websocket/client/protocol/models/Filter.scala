package com.worxbend.obs.websocket.client.protocol.models

import com.worxbend.obs.websocket.client.protocol.*

/** Validated view of an OBS Filter object. `raw` retains every field, including unknown additions. */
final class Filter private (
  val raw:            JsonObject,
  val filterName:     String,
  val filterKind:     String,
  val filterIndex:    BigDecimal,
  val filterEnabled:  Boolean,
  val filterSettings: Field[JsonObject],
) extends ObjectModel

object Filter:
  val codec: ValueCodec[Filter] = ObjectModel.codec(read = decode)

  def decode(data: JsonObject): Either[ProtocolError, Filter] =
    for
      filterName     <- data.required(name = "filterName", codec = ValueCodec.string)
      filterKind     <- data.required(name = "filterKind", codec = ValueCodec.string)
      filterIndex    <- data.required(name = "filterIndex", codec = ValueCodec.number)
      filterEnabled  <- data.required(name = "filterEnabled", codec = ValueCodec.boolean)
      filterSettings <- data.field(name = "filterSettings", codec = ValueCodec.obj, nullable = true)
    yield new Filter(
      raw            = data,
      filterName     = filterName,
      filterKind     = filterKind,
      filterIndex    = filterIndex,
      filterEnabled  = filterEnabled,
      filterSettings = filterSettings,
    )
