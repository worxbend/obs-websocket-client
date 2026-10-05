package com.worxbend.obs.websocket.client.codegen.schema

import com.github.plokhotnyuk.jsoniter_scala.core.*

/** Scalar enum value accepted as either JSON text or an integer. */
private[codegen] final case class SchemaEnumValue(value: String)
private[codegen] object SchemaEnumValue:
  given JsonValueCodec[SchemaEnumValue] with
    // jsoniter evaluates `nullValue` eagerly as the decode default, so it cannot throw; a JSON null
    // surfaces as this empty sentinel and `SchemaNormalizer` rejects it with the enum name instead
    // of generating a nonsense constant.
    def nullValue: SchemaEnumValue = SchemaEnumValue("")
    def decodeValue(in: JsonReader, default: SchemaEnumValue): SchemaEnumValue =
      if in.isNextToken('"') then
        in.rollbackToken()
        SchemaEnumValue(in.readString(null))
      else
        in.rollbackToken()
        SchemaEnumValue(in.readLong().toString)
    def encodeValue(value: SchemaEnumValue, out: JsonWriter): Unit = out.writeVal(value.value)
