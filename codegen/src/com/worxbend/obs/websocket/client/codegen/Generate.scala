package com.worxbend.obs.websocket.client.codegen

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Locale

/** Offline deterministic schema compiler. No generated timestamps or network access. */
object Generate:
  private val base = "com.worxbend.obs.websocket.client.protocol"
  private given JsonValueCodec[SchemaField] = JsonCodecMaker.make
  private given JsonValueCodec[SchemaRequest] = JsonCodecMaker.make
  private given JsonValueCodec[SchemaEvent] = JsonCodecMaker.make
  private given JsonValueCodec[SchemaEnumEntry] = JsonCodecMaker.make
  private given JsonValueCodec[SchemaEnum] = JsonCodecMaker.make
  private given JsonValueCodec[Schema] = JsonCodecMaker.make
  private given JsonValueCodec[Overrides] = JsonCodecMaker.make
  private given JsonValueCodec[Provenance] = JsonCodecMaker.make

  def main(args: Array[String]): Unit =
    require(args.length == 4, "Expected schema path, output directory, overrides path, provenance path")
    val schemaPath = Path.of(args(0))
    val schemaBytes = readBytes(schemaPath, "protocol schema")
    val schema = parseJson[Schema](schemaBytes, schemaPath, "protocol schema")
    val overridesPath = Path.of(args(2))
    val overrides =
      parseJson[Overrides](readBytes(overridesPath, "generation overrides"), overridesPath, "generation overrides")
    val provenancePath = Path.of(args(3))
    val provenance =
      parseJson[Provenance](readBytes(provenancePath, "schema provenance"), provenancePath, "schema provenance")
    val digest = sha256(schemaBytes)
    if digest != provenance.sha256 then
      throw new IllegalArgumentException(
        s"Schema checksum $digest does not match ${provenance.sha256} recorded in $provenancePath"
      )
    generate(schema, overrides, provenance).foreach: (name, contents) =>
      val target = Path.of(args(1)).resolve(name)
      val _ = Files.createDirectories(target.getParent)
      val _ = Files.writeString(target, contents, UTF_8)

  private def readBytes(path: Path, description: String): Array[Byte] =
    try Files.readAllBytes(path)
    catch
      case error: Exception =>
        throw new IllegalArgumentException(s"Failed to read $description from $path: ${error.getMessage}", error)

  private def parseJson[A](bytes: Array[Byte], path: Path, description: String)(using
      JsonValueCodec[A]
  ): A =
    try readFromArray[A](bytes)
    catch
      case error: Exception =>
        throw new IllegalArgumentException(s"Failed to parse $description from $path: ${error.getMessage}", error)

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(byte => f"${byte & 0xff}%02x").mkString

  private[codegen] def generate(
      schema: Schema,
      overrides: Overrides,
      provenance: Provenance
  ): Vector[(String, String)] =
    rejectDuplicates("request", schema.requests.map(_.requestType))
    rejectDuplicates("event", schema.events.map(_.eventType))
    rejectDuplicates("enum", schema.enums.map(_.enumType))
    schema.requests.foreach: request =>
      rejectDuplicates(s"field of ${request.requestType}.request", request.requestFields.map(_.valueName))
      rejectDuplicates(s"field of ${request.requestType}.response", request.responseFields.map(_.valueName))
    schema.events.foreach(event =>
      rejectDuplicates(s"field of ${event.eventType}.event", event.dataFields.map(_.valueName))
    )
    schema.enums.foreach(enumeration =>
      rejectDuplicates(s"identifier of enum ${enumeration.enumType}", enumeration.enumIdentifiers.map(_.enumIdentifier))
    )
    rejectResponseNameCollisions(schema.requests.map(_.requestType))
    rejectUnmatchedOverrides(schema, overrides)
    val nullable = overrides.nullableFields.toSet
    val requests = schema.requests
      .sortBy(_.requestType)
      .map: request =>
        val name = request.requestType
        val requestFields = normalize(name, "request", request.requestFields, nullable)
        val responseFields = normalize(name, "response", request.responseFields, nullable)
        s"requests/$name.scala" -> emitRequest(request, requestFields, responseFields, provenance)
    val events = schema.events
      .sortBy(_.eventType)
      .map: event =>
        val fields = normalize(event.eventType, "event", event.dataFields, nullable)
        s"events/${event.eventType}.scala" -> emitEvent(event, fields, provenance)
    val enums = schema.enums.sortBy(_.enumType).map(e => s"enums/${e.enumType}.scala" -> emitEnum(e, provenance))
    (requests ++ events ++ enums).toVector :+
      ("Event.scala" -> emitEventDispatch(schema.events.map(_.eventType).sorted, provenance)) :+
      ("catalog-inventory.tsv" -> inventory(schema)) :+
      ("Catalog.scala" -> emitCatalog(schema, provenance)) :+
      ("RequestApi.scala" -> emitRequestApi(schema, nullable, provenance))

  /** A protocol-only facade: the error parameter does not prescribe an effect runtime or a transport. */
  private def emitRequestApi(schema: Schema, nullable: Set[String], provenance: Provenance): String =
    val groups = schema.requests.groupBy(_.category).toList.sortBy(_._1)
    def categoryName(category: String): String =
      val words = category.split(" ").toList
      val name = words.head + words.tail.map(_.capitalize).mkString
      if name == "config" then "configuration" else name
    groups.foreach: (category, _) =>
      validateIdentifier("RequestApi", "category", categoryName(category))
    rejectDuplicates("category", groups.map((category, _) => categoryName(category)))
    val categories = groups.map: (category, requests) =>
      val group = categoryName(category)
      val className = s"${group.head.toUpper}${group.tail}Api"
      val methods = requests
        .sortBy(_.requestType)
        .map: request =>
          val name = request.requestType
          val method = s"${name.head.toLower}${name.tail}"
          val fields = normalize(name, "request", request.requestFields, nullable)
          val args = fields.map(field => s"`${identifier(field)}`").mkString(", ")
          s"  /** Executes [[$base.requests.$name]] using the owning request executor. */\n" +
            s"  def $method(${parameters(fields)}): Either[E, requests.${name}Response] =\n" +
            s"    executor.request(requests.$name($args))\n"
      (group, className, methods.mkString("\n"))
    header(base, provenance, withImport = false) +
      "/** Discoverable categories for the full pinned catalog. Implementations retain ownership of request policy. */\n" +
      "trait RequestApi[E]:\n  def request[A](request: Request[A]): Either[E, A]\n" +
      categories.map((group, cls, _) => s"  val $group: $cls[E] = new $cls(this)\n").mkString + "\n" +
      categories
        .map((_, cls, methods) => s"final class $cls[E] private[protocol] (executor: RequestApi[E]):\n$methods")
        .mkString("\n")

  private def rejectDuplicates(kind: String, names: List[String]): Unit =
    val duplicates = names.groupBy(identity).filter((_, occurrences) => occurrences.sizeIs > 1).keys.toList.sorted
    if duplicates.nonEmpty then
      throw new IllegalArgumentException(s"Duplicate $kind names in schema: ${duplicates.mkString(", ")}")

  /** A request named `${other}Response` would emit a case class colliding with the response class of `other`. */
  private def rejectResponseNameCollisions(names: List[String]): Unit =
    val present = names.toSet
    val colliding = names.filter(name => present.contains(name + "Response")).distinct.sorted
    if colliding.nonEmpty then
      throw new IllegalArgumentException(
        s"Request names collide with generated response classes: ${colliding.map(_ + "Response").mkString(", ")}"
      )

  /** Every `nullableFields` key must name a real schema field; a typo would otherwise silently flip nullability.
    * Documentation-only override keys (`numberPolicy`, `objectPolicy`) never reach the IR and stay tolerated.
    */
  private def rejectUnmatchedOverrides(schema: Schema, overrides: Overrides): Unit =
    val requestKeys = schema.requests.flatMap: request =>
      request.requestFields.map(field => s"${request.requestType}.request.${field.valueName}") ++
        request.responseFields.map(field => s"${request.requestType}.response.${field.valueName}")
    val eventKeys = schema.events.flatMap: event =>
      event.dataFields.map(field => s"${event.eventType}.event.${field.valueName}")
    val unmatched = overrides.nullableFields.filterNot((requestKeys ++ eventKeys).toSet).sorted
    if unmatched.nonEmpty then
      throw new IllegalArgumentException(s"Unmatched nullableFields overrides: ${unmatched.mkString(", ")}")

  private def normalize(owner: String, kind: String, fields: List[SchemaField], nullable: Set[String]): List[Field] =
    fields.map: field =>
      validateIdentifier(owner, kind, field.valueName)
      val (scalaType, codec) = field.valueType match
        case "String"        => "String" -> "ValueCodec.string"
        case "Number"        => "BigDecimal" -> "ValueCodec.number"
        case "Boolean"       => "Boolean" -> "ValueCodec.boolean"
        case "Object"        => "JsonObject" -> "ValueCodec.obj"
        case "Any"           => "JsonValue" -> "ValueCodec.json"
        case "Array<Object>" => "Vector[JsonObject]" -> "ValueCodec.array(ValueCodec.obj)"
        case "Array<String>" => "Vector[String]" -> "ValueCodec.array(ValueCodec.string)"
        case unknown         =>
          throw new IllegalArgumentException(s"Unsupported schema type: $unknown at $owner.$kind.${field.valueName}")
      Field(
        field.valueName,
        scalaType,
        codec,
        field.valueOptional,
        nullable.contains(s"$owner.$kind.${field.valueName}"),
        List(
          Some(field.valueDescription),
          field.valueRestrictions.map("Restrictions: " + _),
          field.valueOptionalBehavior.map("When omitted: " + _)
        ).flatten.filter(_.nonEmpty).mkString(" ")
      )

  /** Field names that would shadow members the generator (or the case class itself) emits on every payload. */
  private val reservedIdentifiers =
    Set("toJson", "decodeResponse", "copy", "productPrefix", "productArity", "productElement", "productIterator")

  private def validateIdentifier(owner: String, kind: String, name: String): Unit =
    if name.isEmpty then throw new IllegalArgumentException(s"Empty field name at $owner.$kind")
    else if name.contains('`') || name.split("\\.", -1).exists(!_.matches("[A-Za-z_][A-Za-z0-9_]*")) then
      // Names are always emitted backticked; dotted segments (e.g. `keyModifiers.shift`) are legal.
      throw new IllegalArgumentException(s"Invalid Scala identifier at $owner.$kind.$name")
    else if reservedIdentifiers.contains(name) then
      throw new IllegalArgumentException(s"Field name at $owner.$kind.$name collides with a generated member")

  private def identifier(field: Field): String =
    if Set("requestType", "requestData", "eventType", "eventData").contains(field.name) then
      "payload" + field.name.head.toUpper + field.name.tail
    else field.name

  private def parameters(fields: List[Field]): String = fields
    .map: field =>
      val tpe = if field.optional then s"Field[${field.scalaType}]"
      else if field.nullable then s"Option[${field.scalaType}]"
      else field.scalaType
      val default = if field.optional then " = Field.Missing" else ""
      s"`${identifier(field)}`: $tpe$default"
    .mkString(", ")

  private def codec(field: Field): String =
    if field.nullable && !field.optional then s"ValueCodec.nullable(${field.codec})" else field.codec

  private def encode(fields: List[Field]): String =
    if fields.isEmpty then "JsonObject.empty"
    else
      (if fields.exists(_.name.contains('.')) then "NestedFields.encode(" else "JsonObject(") + fields
        .map: field =>
          if field.optional then s"ValueCodec.put(${quote(field.name)}, `${identifier(field)}`, ${field.codec})"
          else s"Map(${quote(field.name)} -> ${codec(field)}.encode(`${identifier(field)}`))"
        .mkString(" ++ ") + ")"

  private def decode(name: String, fields: List[Field]): String =
    if fields.isEmpty then
      s"\n    val _ = data // Empty payloads deliberately accept unknown future fields.\n    Right($name())"
    else
      val reads = fields.map: field =>
        val read = if field.optional && field.name.contains('.') then
          s"NestedFields.field(data, ${quote(field.name)}, ${field.codec}, ${field.nullable})"
        else if field.optional then s"data.field(${quote(field.name)}, ${field.codec}, ${field.nullable})"
        else if field.name.contains('.') then s"NestedFields.required(data, ${quote(field.name)}, ${codec(field)})"
        else s"data.required(${quote(field.name)}, ${codec(field)})"
        s"      `${identifier(field)}` <- $read"
      "\n    for\n" + reads
        .mkString("\n") + s"\n    yield $name(" + fields.map(f => s"`${identifier(f)}`").mkString(", ") + ")"

  /** Escapes a schema string for embedding in a generated Scala string literal. */
  private def quote(value: String): String = value
    .flatMap:
      case '"'          => "\\\""
      case '\\'         => "\\\\"
      case c if c < ' ' => f"\\u${c.toInt}%04x"
      case c            => c.toString
    .mkString("\"", "", "\"")

  /** Placeholder literal for a required request parameter, keyed by emitted Scala type. */
  private val placeholders = Map(
    "String" -> "\"\"",
    "BigDecimal" -> "BigDecimal(0)",
    "Boolean" -> "false",
    "JsonObject" -> "JsonObject.empty",
    "JsonValue" -> "JsonValue.Null",
    "Vector[JsonObject]" -> "Vector.empty",
    "Vector[String]" -> "Vector.empty"
  )

  private def placeholder(field: Field): String = if field.nullable then "None" else placeholders(field.scalaType)

  /** Collapses upstream Markdown descriptions onto one deterministic Scaladoc line. */
  private def collapse(description: String): String =
    description.trim.replaceAll("\\s+", " ").replace("*/", "* /")

  private def anchor(name: String): String = name.toLowerCase(Locale.ROOT)

  /** One-or-two-line Scaladoc with the upstream reference for the pinned revision. */
  private def documentation(
      summary: String,
      name: String,
      initialVersion: String,
      rpcVersion: String,
      deprecated: Boolean,
      provenance: Provenance,
      fields: List[Field]
  ): String =
    val versions = List(
      Option.when(initialVersion.nonEmpty)(s"initial version $initialVersion"),
      Option.when(rpcVersion.nonEmpty)(s"RPC version $rpcVersion")
    ).flatten
    val since = if versions.nonEmpty then versions.mkString(" (", ", ", ")") else ""
    val deprecation = if deprecated then " Deprecated upstream." else ""
    val title = if summary.nonEmpty then collapse(summary) else s"Generated binding for $name."
    s"""/** $title
       |  *
       |  * Upstream: [[${provenance.upstreamDocs}#${anchor(name)} $name]]$since.$deprecation
       |${parameterDocs(fields)}  */
       |""".stripMargin

  private def parameterDocs(fields: List[Field]): String =
    fields.map(field => s"  * @param `${identifier(field)}` ${collapse(field.description)}\n").mkString

  /** Scaladoc pointing at the owning entry's upstream anchor, with optional field semantics. */
  private def seeDoc(
      summary: String,
      name: String,
      anchorTarget: String,
      provenance: Provenance,
      fields: List[Field] = Nil
  ): String =
    s"""/** $summary
       |  *
       |  * Upstream: [[${provenance.upstreamDocs}#${anchor(anchorTarget)} $name]]
       |${parameterDocs(fields)}  */
       |""".stripMargin

  private def header(pkg: String, provenance: Provenance, withImport: Boolean): String =
    s"// Generated from the pinned OBS schema (sha256: ${provenance.sha256}). Do not edit.\npackage $pkg\n\n" +
      (if withImport then s"import $base.*\n\n" else "")

  private def emitRequest(
      request: SchemaRequest,
      requestFields: List[Field],
      responseFields: List[Field],
      provenance: Provenance
  ): String =
    val name = request.requestType
    header(s"$base.requests", provenance, withImport = true) +
      documentation(
        request.description,
        name,
        request.initialVersion,
        request.rpcVersion,
        request.deprecated,
        provenance,
        requestFields
      ) +
      s"final case class $name(${parameters(requestFields)}) extends Request[${name}Response]:\n" +
      s"  def requestType: String = ${quote(name)}\n" +
      s"  def requestData: JsonObject = ${encode(requestFields)}\n" +
      s"  def decodeResponse(data: JsonObject): Either[ProtocolError, ${name}Response] = ${name}Response.decode(data)\n\n" +
      seeDoc(s"JSON decoder for [[$name]] requests.", name, name, provenance) +
      s"object $name:\n" +
      s"  // Baseline request with every optional field omitted; exercises the constructor defaults.\n" +
      s"  private[protocol] def minimal: $name = $name(" +
      requestFields.filter(!_.optional).map(f => s"`${identifier(f)}` = ${placeholder(f)}").mkString(", ") +
      s")\n  def decode(data: JsonObject): Either[ProtocolError, $name] = ${decode(name, requestFields)}\n\n" +
      seeDoc(s"Response payload for [[$name]].", s"${name}Response", name, provenance, responseFields) +
      s"final case class ${name}Response(${parameters(responseFields)}):\n  def toJson: JsonObject = ${encode(responseFields)}\n\n" +
      seeDoc(s"JSON decoder for [[${name}Response]].", s"${name}Response", name, provenance) +
      s"object ${name}Response:\n  def decode(data: JsonObject): Either[ProtocolError, ${name}Response] = ${decode(name + "Response", responseFields)}\n"

  private def emitEvent(event: SchemaEvent, fields: List[Field], provenance: Provenance): String =
    val name = event.eventType
    header(s"$base.events", provenance, withImport = true) +
      documentation(
        event.description,
        name,
        event.initialVersion,
        event.rpcVersion,
        event.deprecated,
        provenance,
        fields
      ) +
      s"final case class $name(${parameters(fields)}) extends Event:\n" +
      s"  def eventType: String = ${quote(name)}\n" +
      s"  def eventData: JsonObject = ${encode(fields)}\n\n" +
      seeDoc(s"JSON decoder for [[$name]] events.", name, name, provenance) +
      s"object $name:\n" +
      s"  val selector: EventSelector[$name] = EventSelector(${quote(name)}):\n" +
      s"    case event: $name => Some(event)\n    case _ => None\n" +
      s"  def decode(data: JsonObject): Either[ProtocolError, $name] = ${decode(name, fields)}\n"

  private def emitEventDispatch(names: List[String], provenance: Provenance): String =
    header(base, provenance, withImport = false) +
      "/** Typed OBS event with its decoded payload. */\ntrait Event:\n  def eventType: String\n  def eventData: JsonObject\n\n" +
      "/** Raw representation for events unknown to the pinned catalog. */\n" +
      "final case class UnknownEvent(eventType: String, eventData: JsonObject) extends Event\n\n" +
      "/** Event envelope dispatch by `eventType`; unknown events decode to [[UnknownEvent]]. */\n" +
      "object Event:\n  def decode(eventType: String, data: JsonObject): Either[ProtocolError, Event] = eventType match\n" +
      names.map(name => s"    case ${quote(name)} => events.$name.decode(data)").mkString("\n") +
      "\n    case _ => Right(UnknownEvent(eventType, data))\n"

  private def emitCatalog(schema: Schema, provenance: Provenance): String =
    val names = schema.requests.map(_.requestType).sorted
    header(base, provenance, withImport = false) +
      "/** Generated catalog of the pinned request types with typed decoders and raw fallbacks. */\nobject Catalog:\n" +
      "  val requestNames: Vector[String] = Vector(" + names.map(quote).mkString(", ") + ")\n" +
      "  private[protocol] val minimalRequests: Vector[Request[?]] = Vector(" +
      names.map(n => s"requests.$n.minimal").mkString(", ") + ")\n" +
      emitDispatch(
        "decodeRequest",
        "Request[?]",
        names,
        "Right(RawRequest(name, data))",
        n => s"requests.$n.decode(data)"
      ) +
      emitDispatch(
        "roundTripResponse",
        "JsonObject",
        names,
        "Right(data)",
        n => s"requests.${n}Response.decode(data).map(_.toJson)"
      )

  private def emitDispatch(
      method: String,
      result: String,
      names: List[String],
      fallback: String,
      call: String => String
  ): String =
    // Chunked Map literals keep generated method bytecode well below JVM class-file limits.
    val groups = names.grouped(24).toList.zipWithIndex
    val decoderType = s"Map[String, JsonObject => Either[ProtocolError, $result]]"
    val helpers = groups.map: (group, index) =>
      s"  private def $method$index: $decoderType = Map(\n" +
        group.map(n => s"    ${quote(n)} -> ((data: JsonObject) => ${call(n)})").mkString(",\n") + "\n  )\n"
    s"  private val ${method}Decoders: $decoderType = " + (if groups.isEmpty then "Map.empty"
                                                           else
                                                             groups
                                                               .map((_, index) => s"$method$index")
                                                               .mkString(" ++ ")) + "\n" +
      s"  def $method(name: String, data: JsonObject): Either[ProtocolError, $result] =\n" +
      s"    ${method}Decoders.get(name).fold[Either[ProtocolError, $result]]($fallback)(_(data))\n" + helpers.mkString(
        "\n"
      )

  private enum EnumKind:
    case Str, Long

  private def isNumeric(value: String): Boolean = value.matches("-?\\d+")
  private def isBitmask(value: String): Boolean = value.matches("\\([0-9A-Za-z_|<>&\\s]+\\)")

  /** Identifier tokens inside a parenthesized mask; numeric literals and operators pass through untouched. */
  private val maskIdentifier = "[A-Za-z_][0-9A-Za-z_]*".r

  /** Long only when every value is numeric or a parenthesized bitmask; String when every value is textual; a genuinely
    * mixed enum is a schema error, not a guess. Empty values come from JSON nulls in the schema.
    */
  private def classify(name: String, values: List[String]): EnumKind =
    if values.exists(_.isEmpty) then
      throw new IllegalArgumentException(s"Enum $name has an empty enum value (JSON null in the schema)")
    val stringy = values.filter(value => !isNumeric(value) && !isBitmask(value))
    if stringy.isEmpty then EnumKind.Long
    else if stringy.sizeIs == values.size then EnumKind.Str
    else
      throw new IllegalArgumentException(
        s"Enum $name mixes numeric/bitmask and string values: ${stringy.sorted.mkString(", ")}"
      )

  private def emitEnum(enumeration: SchemaEnum, provenance: Provenance): String =
    val name = enumeration.enumType
    val kind = classify(name, enumeration.enumIdentifiers.map(_.enumValue.value))
    val tpe = kind match
      case EnumKind.Str  => "String"
      case EnumKind.Long => "Long"
    val constants = enumeration.enumIdentifiers.map: entry =>
      val raw = entry.enumValue.value
      val value = kind match
        case EnumKind.Str  => quote(raw)
        case EnumKind.Long =>
          // Every identifier in a mask is a sibling constant reference, whatever operator combines it;
          // backticks keep keyword-named identifiers (e.g. `Type`) legal.
          if raw.startsWith("(") then maskIdentifier.replaceAllIn(raw, m => s"`${m.matched}`.value")
          else raw
      val doc = if entry.description.nonEmpty then s"  /** ${collapse(entry.description)} */\n" else ""
      s"$doc  val `${entry.enumIdentifier}`: $name = $name($value)"
    header(s"$base.enums", provenance, withImport = false) +
      seeDoc(s"$name values; unknown values are preserved as data.", name, name, provenance) +
      s"final case class $name(value: $tpe)\n\n" +
      seeDoc(s"Published constants for [[$name]].", name, name, provenance) +
      s"object $name:\n" + constants.mkString("\n") + "\n"

  private def inventory(schema: Schema): String =
    def row(kind: String, name: String, initialVersion: String, fields: List[SchemaField]): String =
      val restrictions = collapse(fields.flatMap(_.valueRestrictions).mkString("; "))
      s"$kind\t$name\t$initialVersion\tgenerated; live OBS verification deferred\t$restrictions"
    "kind\tname\tinitial-version\tstatus\trestrictions\n" +
      (schema.requests.map(r => row("request", r.requestType, r.initialVersion, r.requestFields)) ++
        schema.events.map(e => row("event", e.eventType, e.initialVersion, e.dataFields))).sorted.mkString("\n") + "\n"

private[codegen] final case class Field(
    name: String,
    scalaType: String,
    codec: String,
    optional: Boolean,
    nullable: Boolean,
    description: String
)
