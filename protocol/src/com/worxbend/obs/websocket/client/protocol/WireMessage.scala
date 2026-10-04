package com.worxbend.obs.websocket.client.protocol

final case class WireMessage(op: Int, data: JsonObject)

object Protocol:
  /** Default frame byte limit; ObsConfig.maxMessageBytes starts from this value. */
  val defaultMaxBytes: Int = 1024 * 1024

  def decode(text: String, maxBytes: Int = defaultMaxBytes): Either[ProtocolError, WireMessage] = for
    json <- JsonValue.parse(text, maxBytes)
    envelope <- ValueCodec.obj.decode(json, "$")
    op <- envelope.int("op")
    data <- envelope.obj("d")
  yield WireMessage(op, data)

  def encode(message: WireMessage): String = JsonValue.render(
    JsonObject(
      Map(
        "op" -> JsonValue.Num(BigDecimal(message.op)),
        "d" -> message.data
      )
    )
  )
