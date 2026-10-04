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
          val fields = Map.newBuilder[String, JsonValue]
          if !in.isNextToken('}') then
            in.rollbackToken()
            // Duplicate detection shares one mutable set: HashSet.add hashes once per key and
            // allocates nothing per key, unlike rebuilding an immutable Set per entry.
            val keys = scala.collection.mutable.HashSet.empty[String]
            var more = true
            while more do
              val key = in.readKeyAsString()
              if !keys.add(key) then in.decodeError("duplicate JSON key")
              val _ = fields += key -> read(in, depth + 1)
              more = in.isNextToken(',')
            if !in.isCurrentToken('}') then in.decodeError("expected object end")
          JsonObject(fields.result())
        case '[' =>
          val values = Vector.newBuilder[JsonValue]
          if !in.isNextToken(']') then
            in.rollbackToken()
            var more = true
            while more do
              val _ = values += read(in, depth + 1)
              more = in.isNextToken(',')
            if !in.isCurrentToken(']') then in.decodeError("expected array end")
          Arr(values.result())
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
        // Per-object key sorting (O(k log k)) buys canonical, test-deterministic output; accepted at OBS message rates.
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

  /** Decoding errors contain structure only, never the potentially secret input. jsoniter appends a payload hex dump to
    * every message, so only whitelisted fixed reasons may surface; everything else stays "Malformed JSON".
    */
  def parse(text: String, maxBytes: Int = Protocol.defaultMaxBytes): Either[ProtocolError, JsonValue] =
    // The UTF-8 byte count subsumes the char count: every char encodes to at least one byte.
    val bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    if maxBytes < 1 || bytes.length > maxBytes then Left(ProtocolError.SizeLimit)
    else Try(readFromArray[JsonValue](bytes)).toEither.left.map(decodeFailure)

  /** Fixed structural reasons from this codec and jsoniter's numeric limits; they describe shape, never content. */
  private val structuralReasons = List(
    "JSON nesting exceeds 64",
    "duplicate JSON key",
    "value exceeds limit for number of digits",
    "value exceeds limit for scale"
  )

  // Package-visible for tests: a non-jsoniter throwable may carry a null message and must still
  // collapse to "Malformed JSON" instead of NPEing.
  private[protocol] def decodeFailure(error: Throwable): ProtocolError =
    ProtocolError(
      "$",
      structuralReasons
        .find(reason => Option(error.getMessage).exists(_.startsWith(s"$reason, offset:")))
        .getOrElse("Malformed JSON")
    )

  def render(value: JsonValue): String = writeToString(value)

/** The `parent` path tracks nesting for errors only and is excluded from equality. Single-list apply/copy remain
  * source-compatible.
  */
final case class JsonObject(fields: Map[String, JsonValue])(private val parent: String = "") extends JsonValue:
  def copy(fields: Map[String, JsonValue]): JsonObject = new JsonObject(fields)(parent)
  def copy(): JsonObject = new JsonObject(fields)(parent)
  private[protocol] def at(path: String): JsonObject = new JsonObject(fields)(path)
  private def child(name: String): String = if parent.isEmpty then name else s"$parent.$name"
  def string(name: String): Either[ProtocolError, String] = required(name, ValueCodec.string)
  def int(name: String): Either[ProtocolError, Int] =
    required(name, ValueCodec.number).flatMap: value =>
      value.toBigIntExact
        .filter(_.isValidInt)
        .map(_.intValue)
        .toRight(ProtocolError(child(name), "Expected 32-bit integer"))
  def obj(name: String): Either[ProtocolError, JsonObject] = required(name, ValueCodec.obj)
  def array(name: String): Either[ProtocolError, Vector[JsonValue]] = required(name, ValueCodec.array(ValueCodec.json))
  def boolean(name: String): Either[ProtocolError, Boolean] = required(name, ValueCodec.boolean)
  def optionalString(name: String): Either[ProtocolError, Option[String]] =
    fields.get(name) match
      case None        => Right(None)
      case Some(value) => ValueCodec.string.decode(value, child(name)).map(Some(_))
  def required[A](name: String, codec: ValueCodec[A]): Either[ProtocolError, A] =
    val path = child(name)
    fields.get(name).toRight(ProtocolError(path, "Required field is missing")).flatMap(codec.decode(_, path))
  def field[A](name: String, codec: ValueCodec[A], nullable: Boolean): Either[ProtocolError, Field[A]] =
    fields.get(name) match
      case None                             => Right(Field.Missing)
      case Some(JsonValue.Null) if nullable => Right(Field.Null)
      case Some(value)                      => codec.decode(value, child(name)).map(Field.Value(_))

object JsonObject:
  def apply(fields: Map[String, JsonValue]): JsonObject = new JsonObject(fields)("")
  val empty: JsonObject = JsonObject(Map.empty)
