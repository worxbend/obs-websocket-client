package com.worxbend.obs.websocket.client.bench

import com.github.plokhotnyuk.jsoniter_scala.core.*

/** Verbatim copy of the pre-optimization `protocol.JsonValue` codec (commit 7185fb1): objects accumulated with per-key
  * `Map.updated` and arrays with per-element `:+`. Kept private to the benchmark for A/B comparison against the current
  * `Map.newBuilder`/`Vector.newBuilder` implementation.
  */
sealed trait LegacyJsonValue

object LegacyJsonValue:
  final case class Str(value: String) extends LegacyJsonValue
  final case class Num(value: BigDecimal) extends LegacyJsonValue
  final case class Bool(value: Boolean) extends LegacyJsonValue
  final case class Arr(value: Vector[LegacyJsonValue]) extends LegacyJsonValue
  case object Null extends LegacyJsonValue

  given codec: JsonValueCodec[LegacyJsonValue] with
    def nullValue: LegacyJsonValue = Null
    def decodeValue(in: JsonReader, default: LegacyJsonValue): LegacyJsonValue = read(in, 0)
    private def read(in: JsonReader, depth: Int): LegacyJsonValue =
      if depth > 64 then in.decodeError("JSON nesting exceeds 64")
      in.nextToken() match
        case '{' =>
          var fields = Map.empty[String, LegacyJsonValue]
          if !in.isNextToken('}') then
            in.rollbackToken()
            var more = true
            while more do
              val key = in.readKeyAsString()
              if fields.contains(key) then in.decodeError("duplicate JSON key")
              fields = fields.updated(key, read(in, depth + 1))
              more = in.isNextToken(',')
            if !in.isCurrentToken('}') then in.decodeError("expected object end")
          LegacyJsonObject(fields)
        case '[' =>
          var values = Vector.empty[LegacyJsonValue]
          if !in.isNextToken(']') then
            in.rollbackToken()
            var more = true
            while more do
              values = values :+ read(in, depth + 1)
              more = in.isNextToken(',')
            if !in.isCurrentToken(']') then in.decodeError("expected array end")
          Arr(values)
        case '"' =>
          in.rollbackToken()
          Str(in.readString(null))
        case 't' | 'f' =>
          in.rollbackToken()
          Bool(in.readBoolean())
        case 'n' => in.readNullOrError(Null, "expected JSON value")
        case _   =>
          in.rollbackToken()
          Num(in.readBigDecimal(null, java.math.MathContext.UNLIMITED, 6178, 308))
    def encodeValue(value: LegacyJsonValue, out: JsonWriter): Unit = value match
      case LegacyJsonObject(fields) =>
        out.writeObjectStart()
        val ordered = if fields.size < 2 then fields.toVector else fields.toVector.sortBy(_._1)
        ordered.foreach: (key, entry) =>
          out.writeKey(key)
          encodeValue(entry, out)
        out.writeObjectEnd()
      case Str(value)  => out.writeVal(value)
      case Num(value)  => out.writeVal(value)
      case Bool(value) => out.writeVal(value)
      case Arr(values) =>
        out.writeArrayStart()
        values.foreach(encodeValue(_, out))
        out.writeArrayEnd()
      case Null => out.writeNull()

final case class LegacyJsonObject(fields: Map[String, LegacyJsonValue]) extends LegacyJsonValue
