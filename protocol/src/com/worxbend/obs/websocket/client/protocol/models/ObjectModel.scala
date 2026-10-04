package com.worxbend.obs.websocket.client.protocol.models

import com.worxbend.obs.websocket.client.protocol.*

/** Read-only, validated projection; serialization always preserves the complete original object. */
trait ObjectModel:
  def raw: JsonObject
  final def toJson: JsonObject = raw

private[models] object ObjectModel:
  def codec[A <: ObjectModel](read: JsonObject => Either[ProtocolError, A]): ValueCodec[A] = new ValueCodec[A]:
    def decode(value: JsonValue, path: String): Either[ProtocolError, A] =
      ValueCodec.obj.decode(value, path).flatMap(read)
    def encode(value: A): JsonValue = value.raw
