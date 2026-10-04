package com.worxbend.obs.websocket.client.protocol

/** Structural codecs shared by generated bindings; unknown object keys are retained. */
trait ValueCodec[A]:
  def decode(value: JsonValue, path: String): Either[ProtocolError, A]
  def encode(value: A): JsonValue

object ValueCodec:
  val string: ValueCodec[String] = simple[String]("string")(JsonValue.Str.apply):
    case JsonValue.Str(v) => v
  val number: ValueCodec[BigDecimal] = simple[BigDecimal]("number")(JsonValue.Num.apply):
    case JsonValue.Num(v) => v
  val boolean: ValueCodec[Boolean] = simple[Boolean]("boolean")(JsonValue.Bool.apply):
    case JsonValue.Bool(v) => v
  val obj: ValueCodec[JsonObject] = simple[JsonObject]("object")(identity):
    case v: JsonObject => v
  val json: ValueCodec[JsonValue] = simple[JsonValue]("JSON value")(identity):
    case v => v

  private def simple[A](expected: String)(write: A => JsonValue)(read: PartialFunction[JsonValue, A]): ValueCodec[A] =
    new ValueCodec[A]:
      def decode(value: JsonValue, path: String): Either[ProtocolError, A] =
        read.lift(value).toRight(ProtocolError(path, s"Expected $expected"))
      def encode(value: A): JsonValue = write(value)

  def array[A](element: ValueCodec[A]): ValueCodec[Vector[A]] = new ValueCodec[Vector[A]]:
    def decode(value: JsonValue, path: String): Either[ProtocolError, Vector[A]] = value match
      case JsonValue.Arr(values) =>
        values.zipWithIndex.foldLeft[Either[ProtocolError, Vector[A]]](Right(Vector.empty)):
          case (acc, (entry, index)) =>
            for
              result <- acc
              decoded <- element.decode(entry, s"$path[$index]")
            yield result :+ decoded
      case _ => Left(ProtocolError(path, "Expected array"))
    def encode(value: Vector[A]): JsonValue = JsonValue.Arr(value.map(element.encode))

  def nullable[A](element: ValueCodec[A]): ValueCodec[Option[A]] = new ValueCodec[Option[A]]:
    def decode(value: JsonValue, path: String): Either[ProtocolError, Option[A]] = value match
      case JsonValue.Null => Right(None)
      case other          => element.decode(other, path).map(Some(_))
    def encode(value: Option[A]): JsonValue = value.fold[JsonValue](JsonValue.Null)(element.encode)

  def put[A](name: String, field: Field[A], codec: ValueCodec[A]): Map[String, JsonValue] = field match
    case Field.Missing      => Map.empty
    case Field.Null         => Map(name -> JsonValue.Null)
    case Field.Value(value) => Map(name -> codec.encode(value))
