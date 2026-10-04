package com.worxbend.obs.websocket.client.protocol

/** Schema dotted paths describe nested JSON, not literal wire keys. Explicit child fields override parent keys. */
private[protocol] object NestedFields:
  def encode(fields: Map[String, JsonValue]): JsonObject =
    val parents = fields.filterNot(_._1.contains('.'))
    JsonObject(
      fields.toVector
        .filter(_._1.contains('.'))
        .sortBy(_._1)
        .foldLeft(parents):
          case (current, (path, value)) => put(current, path.split("\\.").toList, value)
    )

  private def put(fields: Map[String, JsonValue], path: List[String], value: JsonValue): Map[String, JsonValue] =
    if path.size == 1 then fields.updated(path.head, value)
    else
      val parent = path.head
      fields.get(parent) match
        case None                  => fields.updated(parent, JsonObject(put(Map.empty, path.tail, value)))
        case Some(obj: JsonObject) => fields.updated(parent, JsonObject(put(obj.fields, path.tail, value)))
        // Preserve an invalid explicit parent so request validation rejects it instead of silently repairing it.
        case Some(_) => fields

  def field[A](
      data: JsonObject,
      name: String,
      codec: ValueCodec[A],
      nullable: Boolean
  ): Either[ProtocolError, Field[A]] =
    val separator = name.indexOf('.')
    if separator < 0 then data.field(name, codec, nullable)
    else
      val parent = name.take(separator)
      if !data.fields.contains(parent) then Right(Field.Missing)
      else
        data
          .required(parent, ValueCodec.obj)
          .flatMap(nested => field(nested, name.drop(separator + 1), codec, nullable))

  def required[A](data: JsonObject, name: String, codec: ValueCodec[A]): Either[ProtocolError, A] =
    val separator = name.indexOf('.')
    if separator < 0 then data.required(name, codec)
    else
      data
        .required(name.take(separator), ValueCodec.obj)
        .flatMap(nested => required(nested, name.drop(separator + 1), codec))
