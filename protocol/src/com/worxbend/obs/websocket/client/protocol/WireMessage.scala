package com.worxbend.obs.websocket.client.protocol

final case class WireMessage(op: Int, data: JsonObject)

object Protocol:
  /** Default frame byte limit; ObsConfig.maxMessageBytes starts from this value. Sized for base64 screenshot payloads,
    * which routinely exceed 1 MiB.
    */
  val defaultMaxBytes: Int = 16 * 1024 * 1024

  def decode(text: String, maxBytes: Int = defaultMaxBytes): Either[ProtocolError, WireMessage] =
    for
      json     <- JsonValue.parse(text = text, maxBytes = maxBytes)
      envelope <- ValueCodec.obj.decode(value = json, path = "$")
      op       <- envelope.int(name = "op")
      data     <- envelope.obj(name = "d")
    yield WireMessage(op = op, data = data)

  def encode(message: WireMessage): String = JsonValue.render(
    value = JsonObject(
      fields = Map(
        "op" -> JsonValue.Num(value = BigDecimal(message.op)),
        "d"  -> message.data,
      )
    )
  )
