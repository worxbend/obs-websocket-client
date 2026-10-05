package com.worxbend.obs.websocket.client.codegen.schema

import com.worxbend.obs.websocket.client.codegen.model.*
import com.worxbend.obs.websocket.client.codegen.ScalaLiteral

/** Validates schema semantics and resolves all types, identifiers, overrides, and facade names. Invalid inputs abort
  * this build-time compilation unit with contextual IllegalArgumentExceptions. No file access or output layout belongs
  * here.
  */
private[codegen] object SchemaNormalizer:
  /** Produces immutable rendering inputs; each request payload is normalized only once. */
  def normalize(schema: Schema, overrides: Overrides): NormalizedSchema =
    validateSchema(schema, overrides)
    val nullable = overrides.nullableFields.toSet
    val requests = schema.requests.sortBy(_.requestType).map(normalizeRequest(_, nullable))
    val events = schema.events.sortBy(_.eventType).map(normalizeEvent(_, nullable))
    val enums = schema.enums.sortBy(_.enumType).map(normalizeEnum)
    val categories = normalizeCategories(requests)
    NormalizedSchema(requests, events, enums, categories, inventoryEntries(schema))

  private def validateSchema(schema: Schema, overrides: Overrides): Unit =
    rejectDuplicates("request", schema.requests.map(_.requestType))
    rejectDuplicates("event", schema.events.map(_.eventType))
    rejectDuplicates("enum", schema.enums.map(_.enumType))
    // Field names are checked after the payload rename so a schema field literally named
    // `payloadRequestType` collides loudly with a renamed `requestType` sibling instead of
    // silently emitting two identically named constructor parameters.
    schema.requests.foreach: request =>
      rejectDuplicates(s"field of ${request.requestType}.request", request.requestFields.map(f => renamed(f.valueName)))
      rejectDuplicates(
        s"field of ${request.requestType}.response",
        request.responseFields.map(f => renamed(f.valueName))
      )
    schema.events.foreach(event =>
      rejectDuplicates(s"field of ${event.eventType}.event", event.dataFields.map(f => renamed(f.valueName)))
    )
    schema.enums.foreach(enumeration =>
      rejectDuplicates(s"identifier of enum ${enumeration.enumType}", enumeration.enumIdentifiers.map(_.enumIdentifier))
    )
    rejectResponseNameCollisions(schema.requests.map(_.requestType))
    rejectUnmatchedOverrides(schema, overrides)

  private def normalizeRequest(request: SchemaRequest, nullable: Set[String]): RequestDefinition =
    RequestDefinition(
      request.requestType,
      normalizeFields(request.requestType, "request", request.requestFields, nullable),
      normalizeFields(request.requestType, "response", request.responseFields, nullable),
      Documentation(request.description, request.initialVersion, request.rpcVersion, request.deprecated),
      request.category,
      s"${request.requestType.head.toLower}${request.requestType.tail}"
    )

  private def normalizeEvent(event: SchemaEvent, nullable: Set[String]): EventDefinition =
    EventDefinition(
      event.eventType,
      normalizeFields(event.eventType, "event", event.dataFields, nullable),
      Documentation(event.description, event.initialVersion, event.rpcVersion, event.deprecated)
    )

  private def normalizeCategories(requests: List[RequestDefinition]): List[RequestCategory] =
    val groups = requests.groupBy(_.category).toList.sortBy(_._1)
    groups.foreach: (category, _) =>
      validateIdentifier("RequestApi", "category", categoryName(category))
    rejectDuplicates("category", groups.map((category, _) => categoryName(category)))
    groups.map: (category, members) =>
      val name = categoryName(category)
      RequestCategory(name, s"${name.head.toUpper}${name.tail}Api", members)

  private def categoryName(category: String): String =
    val words = category.split(" ").toList
    val name = s"${words.head}${words.tail.map(_.capitalize).mkString}"
    // Preserve the documented full-word facade for the upstream abbreviated category.
    if name == "config" then "configuration" else name

  private def inventoryEntries(schema: Schema): List[InventoryEntry] =
    schema.requests.map: request =>
      InventoryEntry(
        "request",
        request.requestType,
        request.initialVersion,
        request.requestFields.flatMap(_.valueRestrictions).mkString("; ")
      )
    ++ schema.events.map: event =>
      InventoryEntry(
        "event",
        event.eventType,
        event.initialVersion,
        event.dataFields.flatMap(_.valueRestrictions).mkString("; ")
      )

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

  /** Emitted Scala type, JSON codec, and required-parameter placeholder for one supported schema type. Both field
    * normalization and placeholder selection read the single [[typeMappings]] table, so a newly supported schema type
    * cannot compile while its placeholder is still missing (previously two parallel maps drifted apart).
    */
  private final case class TypeMapping(scalaType: String, codec: String, placeholder: String)

  private val typeMappings = Map(
    "String" -> TypeMapping("String", "ValueCodec.string", "\"\""),
    "Number" -> TypeMapping("BigDecimal", "ValueCodec.number", "BigDecimal(0)"),
    "Boolean" -> TypeMapping("Boolean", "ValueCodec.boolean", "false"),
    "Object" -> TypeMapping("JsonObject", "ValueCodec.obj", "JsonObject.empty"),
    "Any" -> TypeMapping("JsonValue", "ValueCodec.json", "JsonValue.Null"),
    "Array<Object>" -> TypeMapping("Vector[JsonObject]", "ValueCodec.array(ValueCodec.obj)", "Vector.empty"),
    "Array<String>" -> TypeMapping("Vector[String]", "ValueCodec.array(ValueCodec.string)", "Vector.empty")
  )

  private def normalizeFields(
      owner: String,
      kind: String,
      fields: List[SchemaField],
      nullable: Set[String]
  ): List[Field] =
    fields.map: field =>
      validateIdentifier(owner, kind, field.valueName)
      val mapping = typeMappings.getOrElse(
        field.valueType,
        throw new IllegalArgumentException(
          s"Unsupported schema type: ${field.valueType} at $owner.$kind.${field.valueName}"
        )
      )
      Field(
        field.valueName,
        renamed(field.valueName),
        mapping.scalaType,
        mapping.codec,
        field.valueOptional,
        nullable.contains(s"$owner.$kind.${field.valueName}"),
        List(
          Some(field.valueDescription),
          field.valueRestrictions.map(value => s"Restrictions: $value"),
          field.valueOptionalBehavior.map(value => s"When omitted: $value")
        ).flatten.filter(_.nonEmpty).mkString(" "),
        if nullable.contains(s"$owner.$kind.${field.valueName}") then "None" else mapping.placeholder
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

  /** Payload field names that would shadow the `Request`/`Event` envelope members get a `payload` prefix. */
  private def renamed(name: String): String =
    if Set("requestType", "requestData", "eventType", "eventData").contains(name) then
      s"payload${name.head.toUpper}${name.tail}"
    else name

  private enum EnumKind:
    case Str, Long

  private def isNumeric(value: String): Boolean = value.matches("-?\\d+")
  private def isBitmask(value: String): Boolean = value.matches("\\([0-9A-Za-z_|<>&\\s]+\\)")

  /** Identifier tokens inside a parenthesized mask; numeric literals and operators pass through untouched. */
  private val maskIdentifier = "[A-Za-z_][0-9A-Za-z_]*".r

  /** Rewrites sibling identifier references in a parenthesized bitmask to `.value` reads. Every identifier-shaped token
    * must name a declared sibling constant: hex literals (`0x1F` yields the token `x1F`), suffixed numerics (`1L`
    * yields `L`), and unknown identifiers would otherwise silently rewrite into wrong or uncompilable constants.
    * Failures name the enum and the offending mask.
    */
  private def rewriteMask(name: String, raw: String, siblings: Set[String]): String =
    val unknown = maskIdentifier.findAllIn(raw).filterNot(siblings).toList.distinct.sorted
    if unknown.nonEmpty then
      throw new IllegalArgumentException(
        s"Enum $name mask $raw references identifiers that are not declared enum members: ${unknown.mkString(", ")}"
      )
    maskIdentifier.replaceAllIn(raw, m => s"`${m.matched}`.value")

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

  private def normalizeEnum(enumeration: SchemaEnum): EnumDefinition =
    val name = enumeration.enumType
    val siblings = enumeration.enumIdentifiers.map(_.enumIdentifier).toSet
    val kind = classify(name, enumeration.enumIdentifiers.map(_.enumValue.value))
    val scalaType = kind match
      case EnumKind.Str  => "String"
      case EnumKind.Long => "Long"
    val constants = enumeration.enumIdentifiers.map: entry =>
      val raw = entry.enumValue.value
      val expression = kind match
        case EnumKind.Str  => ScalaLiteral.quote(raw)
        case EnumKind.Long => if raw.startsWith("(") then rewriteMask(name, raw, siblings) else raw
      EnumDefinition.Constant(entry.enumIdentifier, expression, entry.description)
    EnumDefinition(name, scalaType, constants)
