package com.worxbend.obs.websocket.client.protocol.models

import com.worxbend.obs.websocket.client.protocol.*

/** Validated view of an OBS Filter object. `raw` retains every field, including unknown additions. */
final class Filter private (
    val raw: JsonObject,
    val filterName: String,
    val filterKind: String,
    val filterIndex: BigDecimal,
    val filterEnabled: Boolean,
    val filterSettings: Field[JsonObject]
) extends ObjectModel

object Filter:
  val codec: ValueCodec[Filter] = ObjectModel.codec(decode)

  def decode(data: JsonObject): Either[ProtocolError, Filter] =
    for
      filterName <- data.required("filterName", ValueCodec.string)
      filterKind <- data.required("filterKind", ValueCodec.string)
      filterIndex <- data.required("filterIndex", ValueCodec.number)
      filterEnabled <- data.required("filterEnabled", ValueCodec.boolean)
      filterSettings <- data.field("filterSettings", ValueCodec.obj, nullable = true)
    yield new Filter(data, filterName, filterKind, filterIndex, filterEnabled, filterSettings)
