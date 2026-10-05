package com.worxbend.obs.websocket.client.protocol.models

import com.worxbend.obs.websocket.client.protocol.*

/** Validated view of an OBS PropertyItem object. `raw` retains every field, including unknown additions. */
final class PropertyItem private (
  val raw:         JsonObject,
  val itemName:    String,
  val itemEnabled: Boolean,
  val itemValue:   JsonValue,
) extends ObjectModel

object PropertyItem:
  val codec: ValueCodec[PropertyItem] = ObjectModel.codec(read = decode)

  def decode(data: JsonObject): Either[ProtocolError, PropertyItem] =
    for
      itemName    <- data.required(name = "itemName", codec = ValueCodec.string)
      itemEnabled <- data.required(name = "itemEnabled", codec = ValueCodec.boolean)
      itemValue   <- data.required(name = "itemValue", codec = ValueCodec.json)
    yield new PropertyItem(raw = data, itemName = itemName, itemEnabled = itemEnabled, itemValue = itemValue)
