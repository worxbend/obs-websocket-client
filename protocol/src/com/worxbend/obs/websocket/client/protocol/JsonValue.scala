package com.worxbend.obs.websocket.client.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.*
import scala.util.Try

/** Lossless JSON values. Numbers deliberately retain decimal precision. */
sealed trait JsonValue

object JsonValue:
  final case class Str(value: String) extends JsonValue
  final case class Num(value: BigDecimal) extends JsonValue
  final case class Bool(value: Boolean) extends JsonValue
  final case class Arr(value: Vector[JsonValue]) extends JsonValue
  case object Null extends JsonValue

  given codec: JsonValueCodec[JsonValue] with
    def nullValue: JsonValue = Null
    def decodeValue(in: JsonReader, default: JsonValue): JsonValue = read(in, 0)
    private def read(in: JsonReader, depth: Int): JsonValue =
      if depth > 64 then in.decodeError("JSON nesting exceeds 64")
      in.nextToken() match
        case '{' =>
          var fields = Map.empty[String, JsonValue]
          if !in.isNextToken('}') then
            in.rollbackToken()
            var more = true
            while more do
              val key = in.readKeyAsString()
              if fields.contains(key) then in.decodeError("duplicate JSON key")
              fields = fields.updated(key, read(in, depth + 1))
              more = in.isNextToken(',')
            if !in.isCurrentToken('}') then in.decodeError("expected object end")
          JsonObject(fields)
        case '[' =>
          var values = Vector.empty[JsonValue]
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
          // Reject excessive numbers rather than silently rounding beyond DECIMAL128 precision.
          Num(in.readBigDecimal(null, java.math.MathContext.UNLIMITED, 6178, 308))
    def encodeValue(value: JsonValue, out: JsonWriter): Unit = value match
      case JsonObject(fields) =>
        out.writeObjectStart()
        fields.toVector
          .sortBy(_._1)
          .foreach: (key, entry) =>
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

  /** Decoding errors contain structure only, never the potentially secret input. */
  def parse(text: String, maxBytes: Int = Protocol.defaultMaxBytes): Either[ProtocolError, JsonValue] =
    if maxBytes < 1 || text.length > maxBytes || text
        .getBytes(java.nio.charset.StandardCharsets.UTF_8)
        .length > maxBytes
    then Left(ProtocolError("$", "JSON exceeds configured byte limit"))
    else Try(readFromString[JsonValue](text)).toEither.left.map(_ => ProtocolError("$", "Malformed JSON"))

  def render(value: JsonValue): String = writeToString(value)

final case class JsonObject(fields: Map[String, JsonValue]) extends JsonValue:
  def string(name: String): Either[ProtocolError, String] = required(name, ValueCodec.string)
  def int(name: String): Either[ProtocolError, Int] =
    required(name, ValueCodec.number).flatMap: value =>
      value.toBigIntExact.filter(_.isValidInt).map(_.intValue).toRight(ProtocolError(name, "Expected 32-bit integer"))
  def obj(name: String): Either[ProtocolError, JsonObject] = required(name, ValueCodec.obj)
  def array(name: String): Either[ProtocolError, Vector[JsonValue]] = required(name, ValueCodec.array(ValueCodec.json))
  def boolean(name: String): Either[ProtocolError, Boolean] = required(name, ValueCodec.boolean)
  def optionalString(name: String): Either[ProtocolError, Option[String]] =
    fields.get(name) match
      case None        => Right(None)
      case Some(value) => ValueCodec.string.decode(value, name).map(Some(_))
  def required[A](name: String, codec: ValueCodec[A]): Either[ProtocolError, A] =
    fields.get(name).toRight(ProtocolError(name, "Required field is missing")).flatMap(codec.decode(_, name))
  def field[A](name: String, codec: ValueCodec[A], nullable: Boolean): Either[ProtocolError, Field[A]] =
    fields.get(name) match
      case None                             => Right(Field.Missing)
      case Some(JsonValue.Null) if nullable => Right(Field.Null)
      case Some(value)                      => codec.decode(value, name).map(Field.Value(_))

object JsonObject:
  val empty: JsonObject = JsonObject(Map.empty)
