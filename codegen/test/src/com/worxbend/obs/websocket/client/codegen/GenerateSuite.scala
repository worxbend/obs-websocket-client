package com.worxbend.obs.websocket.client.codegen

import com.worxbend.obs.websocket.client.codegen.schema.*
import munit.FunSuite
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.MessageDigest

class GenerateSuite extends FunSuite:
  private val schemaFile: String               = "schema.json"
  private val overridesFile: String            = "overrides.json"
  private val provenanceFile: String           = "provenance.json"
  private val maskEnumPath: String             = "enums/Mask.scala"
  private val eventDispatchPath: String        = "Event.scala"
  private val firstBitExpression: String       = "(1 << 0)"
  private val combinedBitsExpression: String   = "(One | Two)"
  private val catalogPath: String              = "Catalog.scala"
  private val nestedRequestPath: String        = "requests/Nested.scala"
  private val zedRequestPath: String           = "requests/Zed.scala"
  private val temporaryDirectoryPrefix: String = "obs-codegen-test"

  private val provenance = Provenance(
    repository = "https://github.com/obsproject/obs-websocket",
    revision   = "0123456789abcdef0123456789abcdef01234567",
    sha256     = "f" * 64,
  )
  private val allFields =
    List("String", "Number", "Boolean", "Object", "Any", "Array<Object>", "Array<String>").zipWithIndex.map:
      case (tpe, index) => SchemaField(valueName = s"field$index", valueType = tpe, valueOptional = index % 2 == 0)
  private val schema = Schema(
    requests = List(
      SchemaRequest(
        requestType    = "Zed",
        requestFields  = allFields,
        responseFields = allFields.map(_.copy(valueOptional = false)),
      ),
      SchemaRequest(requestType = "Empty", requestFields = Nil, responseFields = Nil),
      SchemaRequest(
        requestType    = "Vendor",
        requestFields  = List(SchemaField(valueName = "requestType", valueType = "String")),
        responseFields = Nil,
      ),
    ),
    events = List(
      SchemaEvent(eventType = "Changed", dataFields    = allFields),
      SchemaEvent(eventType = "EmptyEvent", dataFields = Nil),
    ),
  )
  private val overrides = Overrides(nullableFields = List("Zed.request.field0", "Zed.response.field1"))

  test("all output templates preserve the independently captured source fixtures"):
    val directory = Files.createTempDirectory("obs-codegen-golden")
    try
      def resource(name: String): String =
        val stream = getClass.getResourceAsStream(s"/golden/$name")
        try new String(stream.readAllBytes(), UTF_8)
        finally stream.close()
      List(schemaFile, overridesFile, provenanceFile).foreach: name =>
        val _ = Files.writeString(directory.resolve(name), resource(name = name), UTF_8)
      val output = directory.resolve("output")
      Generate.main(
        args = Array(
          directory.resolve(schemaFile).toString,
          output.toString,
          directory.resolve(overridesFile).toString,
          directory.resolve(provenanceFile).toString,
        )
      )
      val names = List(
        "requests/Example.scala",
        "requests/Empty.scala",
        "events/Changed.scala",
        maskEnumPath,
        "enums/State.scala",
        eventDispatchPath,
        catalogPath,
        "RequestApi.scala",
        "catalog-inventory.tsv",
      )
      val expected = names.map(name => name -> resource(name = s"expected/$name")).toMap
      assertEquals(snapshot(directory = output), expected)
    finally deleteRecursively(directory = directory)

  test("category facades preserve names, optionality, selectors, and stable category ordering"):
    val categories = schema.copy(requests =
      schema.requests.map(request =>
        request.copy(category = if request.requestType == "Zed" then "scene items" else "config")
      )
    )
    val generated = Generate.generate(schema = categories, overrides = overrides, provenance = provenance).toMap
    val api       = generated("RequestApi.scala")
    assert(api.contains("val configuration: ConfigurationApi[E]"))
    assert(api.contains("val sceneItems: SceneItemsApi[E]"))
    assert(api.contains("def empty(): Either[E, requests.EmptyResponse]"))
    assert(api.contains("`field0`: Field[String] = Field.Missing"))
    assert(
      api.contains(
        "executor.request(requests.Zed(`field0`, `field1`, `field2`, `field3`, `field4`, `field5`, `field6`))"
      )
    )
    assert(generated("events/Changed.scala").contains("val selector: EventSelector[Changed]"))
    val invalid = schema.copy(requests =
      List(SchemaRequest(requestType = "Empty", requestFields = Nil, responseFields = Nil, category = "bad-name"))
    )
    assert(
      intercept[IllegalArgumentException](
        Generate.generate(schema = invalid, overrides = Overrides(), provenance = provenance)
      ).getMessage
        .contains("category")
    )

  test("generation is deterministic regardless of catalog input order"):
    val first  = Generate.generate(schema = schema, overrides = overrides, provenance = provenance)
    val second =
      Generate.generate(
        schema     = schema.copy(requests = schema.requests.reverse, events = schema.events.reverse),
        overrides  = overrides,
        provenance = provenance,
      )
    assertEquals(first, second)
    assert(first.exists((name, _) => name == "requests/Empty.scala"))
    assert(first.find(_._1 == "requests/Vendor.scala").get._2.contains("payloadRequestType"))
    assert(first.find(_._1 == zedRequestPath).get._2.contains("Option[BigDecimal]"))
    assert(
      first
        .find(_._1 == zedRequestPath)
        .get
        ._2
        .contains(
          "private[protocol] def minimal: Zed = Zed(`field1` = BigDecimal(0), `field3` = JsonObject.empty, `field5` = Vector.empty)"
        )
    )

  test("minimal baselines use None for required nullable fields"):
    val nullable = Schema(
      requests = List(
        SchemaRequest(
          requestType    = "Nullable",
          requestFields  = List(SchemaField(valueName = "slot", valueType = "String")),
          responseFields = Nil,
        )
      ),
      events = Nil,
    )
    val generated = Generate
      .generate(
        schema     = nullable,
        overrides  = Overrides(nullableFields = List("Nullable.request.slot")),
        provenance = provenance,
      )
      .toMap
    assert(generated("requests/Nullable.scala").contains("def minimal: Nullable = Nullable(`slot` = None)"))
    assert(generated(catalogPath).contains("requests.Nullable.minimal"))

  test("unmatched nullable overrides fail with the offending key"):
    val error = intercept[IllegalArgumentException]:
      Generate.generate(
        schema     = schema,
        overrides  = Overrides(nullableFields = List("Zed.request.typo")),
        provenance = provenance,
      )
    assert(error.getMessage.contains("Zed.request.typo"))

  test("duplicate generated names fail per kind with the offenders listed"):
    val requests = schema.copy(requests =
      List(
        SchemaRequest(requestType = "Same", requestFields = Nil, responseFields = Nil),
        SchemaRequest(requestType = "Same", requestFields = Nil, responseFields = Nil),
      )
    )
    assert(
      intercept[IllegalArgumentException](
        Generate.generate(schema = requests, overrides = Overrides(nullableFields = Nil), provenance = provenance)
      ).getMessage
        .contains("Same")
    )
    val events = schema.copy(events =
      List(SchemaEvent(eventType = "Same", dataFields = Nil), SchemaEvent(eventType = "Same", dataFields = Nil))
    )
    assert(
      intercept[IllegalArgumentException](
        Generate.generate(schema = events, overrides = Overrides(nullableFields = Nil), provenance = provenance)
      ).getMessage
        .contains("Same")
    )
    val enums = schema.copy(enums =
      List(
        SchemaEnum(
          enumType        = "Same",
          enumIdentifiers = List(SchemaEnumEntry(enumIdentifier = "One", enumValue = SchemaEnumValue(value = "1"))),
        ),
        SchemaEnum(enumType = "Same", enumIdentifiers = Nil),
      )
    )
    assert(
      intercept[IllegalArgumentException](
        Generate.generate(schema = enums, overrides = Overrides(nullableFields = Nil), provenance = provenance)
      ).getMessage
        .contains("Same")
    )

  test("duplicate field names within one payload fail with the owning field list"):
    val duplicated = SchemaField(valueName = "slot", valueType = "String")
    val request    = schema.copy(requests =
      List(
        SchemaRequest(
          requestType    = "Dupe",
          requestFields  = List(duplicated, duplicated.copy(valueType = "Number")),
          responseFields = List(duplicated, duplicated),
        )
      )
    )
    val requestError = intercept[IllegalArgumentException](
      Generate.generate(schema = request, overrides = Overrides(nullableFields = Nil), provenance = provenance)
    )
    assert(requestError.getMessage.contains("Dupe.request"))
    val event =
      schema.copy(events =
        List(SchemaEvent(eventType = "Dupe", dataFields = List(duplicated, duplicated.copy(valueType = "Boolean"))))
      )
    val eventError = intercept[IllegalArgumentException](
      Generate.generate(schema = event, overrides = Overrides(nullableFields = Nil), provenance = provenance)
    )
    assert(eventError.getMessage.contains("Dupe.event"))

  test("duplicate enum identifiers within one enum fail with the enum name"):
    val duplicated = SchemaEnum(
      enumType        = "Dupe",
      enumIdentifiers = List(
        SchemaEnumEntry(enumIdentifier = "One", enumValue = SchemaEnumValue(value = "1")),
        SchemaEnumEntry(enumIdentifier = "One", enumValue = SchemaEnumValue(value = "2")),
      ),
    )
    val error = intercept[IllegalArgumentException]:
      Generate.generate(schema = schema.copy(enums = List(duplicated)), overrides = overrides, provenance = provenance)
    assert(error.getMessage.contains("Dupe"))
    assert(error.getMessage.contains("One"))

  test("a request named after another request's response class fails"):
    val colliding = schema.copy(requests =
      schema.requests ++ List(SchemaRequest(requestType = "ZedResponse", requestFields = Nil, responseFields = Nil))
    )
    val error = intercept[IllegalArgumentException](
      Generate.generate(schema = colliding, overrides = overrides, provenance = provenance)
    )
    assert(error.getMessage.contains("ZedResponse"))

  test("an empty schema still generates a compilable catalog"):
    val generated = Generate
      .generate(
        schema     = Schema(requests = Nil, events = Nil),
        overrides  = Overrides(nullableFields = Nil),
        provenance = provenance,
      )
      .toMap
    assert(generated(catalogPath).contains("Map.empty"))
    assert(
      !generated(catalogPath).contains(
        "Decoders: Map[String, JsonObject => Either[ProtocolError, Request[?]]] = \n"
      )
    )

  test("invalid field names fail with owner context instead of leaking into generated code"):
    val unnamed = Schema(
      requests = List(
        SchemaRequest(
          requestType    = "Broken",
          requestFields  = List(SchemaField(valueName = "", valueType = "String")),
          responseFields = Nil,
        )
      ),
      events = Nil,
    )
    val empty = intercept[IllegalArgumentException](
      Generate.generate(schema = unnamed, overrides = Overrides(nullableFields = Nil), provenance = provenance)
    )
    assert(empty.getMessage.contains("Broken.request"))
    val symbol = Schema(
      requests = List(
        SchemaRequest(
          requestType    = "Broken",
          requestFields  = List(SchemaField(valueName = "not-a-name", valueType = "String")),
          responseFields = Nil,
        )
      ),
      events = Nil,
    )
    val invalid = intercept[IllegalArgumentException](
      Generate.generate(schema = symbol, overrides = Overrides(nullableFields = Nil), provenance = provenance)
    )
    assert(invalid.getMessage.contains("Broken.request.not-a-name"))
    val ticked = Schema(
      requests = List(
        SchemaRequest(
          requestType    = "Broken",
          requestFields  = List(SchemaField(valueName = "bad`name", valueType = "String")),
          responseFields = Nil,
        )
      ),
      events = Nil,
    )
    val backtick = intercept[IllegalArgumentException](
      Generate.generate(schema = ticked, overrides = Overrides(nullableFields = Nil), provenance = provenance)
    )
    assert(backtick.getMessage.contains("Broken.request.bad`name"))
    val reserved = Schema(
      requests = List(
        SchemaRequest(
          requestType    = "Broken",
          requestFields  = Nil,
          responseFields = List(SchemaField(valueName = "toJson", valueType = "String")),
        )
      ),
      events = Nil,
    )
    val collision = intercept[IllegalArgumentException](
      Generate.generate(schema = reserved, overrides = Overrides(nullableFields = Nil), provenance = provenance)
    )
    assert(collision.getMessage.contains("Broken.response.toJson"))

  test("dotted schema names stay legal backticked identifiers"):
    val dotted = Schema(
      requests = List(
        SchemaRequest(
          requestType    = "Nested",
          requestFields  = List(SchemaField(valueName = "keyModifiers.shift", valueType = "Boolean")),
          responseFields = Nil,
        )
      ),
      events = Nil,
    )
    val generated =
      Generate.generate(schema = dotted, overrides = Overrides(nullableFields = Nil), provenance = provenance).toMap
    assert(generated(nestedRequestPath).contains("`keyModifiers.shift`: Boolean"))
    assert(generated(nestedRequestPath).contains("NestedFields.encode("))
    assert(
      generated(nestedRequestPath).contains(
        "NestedFields.required(data, \"keyModifiers.shift\", ValueCodec.boolean)"
      )
    )

  test("optional dotted fields decode through the parent object with omission semantics"):
    val nested = Schema(
      requests = List(
        SchemaRequest(
          requestType    = "Nested",
          requestFields  = List(SchemaField(valueName = "parent.child", valueType = "Boolean", valueOptional = true)),
          responseFields = Nil,
        )
      ),
      events = Nil,
    )
    val generated = Generate.generate(schema = nested, overrides = Overrides(), provenance = provenance).toMap
    assert(
      generated(nestedRequestPath).contains(
        "NestedFields.field(data, \"parent.child\", ValueCodec.boolean, false)"
      )
    )

  test("overrides files with only documentation keys parse with an empty nullable list"):
    import com.github.plokhotnyuk.jsoniter_scala.core.*
    import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
    assertEquals(
      readFromString[Overrides]("""{"numberPolicy":"documentation only"}""")(using JsonCodecMaker.make),
      Overrides(nullableFields = Nil),
    )

  test("inventory rows collapse restrictions onto one line and include event field restrictions"):
    val field       = SchemaField(valueName = "slot", valueType = "String", valueRestrictions = Some(">= 0,\n\t<= 100"))
    val inventoried = Schema(
      requests = List(SchemaRequest(requestType = "Ranged", requestFields = List(field), responseFields = Nil)),
      events   = List(SchemaEvent(eventType = "Ranged", dataFields = List(field))),
    )
    val generated = Generate
      .generate(schema = inventoried, overrides = Overrides(nullableFields = Nil), provenance = provenance)
      .toMap
    val rows = generated("catalog-inventory.tsv").linesIterator.filter(_.contains("Ranged")).toList
    assertEquals(rows.size, 2)
    assert(rows.forall(_.endsWith("\t>= 0, <= 100")))

  test("unknown schema types fail instead of silently generating untyped bindings"):
    val unknown = Schema(
      requests = List(
        SchemaRequest(
          requestType    = "Unknown",
          requestFields  = List(SchemaField(valueName = "x", valueType = "Mystery")),
          responseFields = Nil,
        )
      ),
      events = Nil,
    )
    val error = intercept[IllegalArgumentException](
      Generate.generate(schema = unknown, overrides = Overrides(nullableFields = Nil), provenance = provenance)
    )
    assert(error.getMessage.contains("Unknown.request.x"))

  test("entrypoint writes deterministic offline outputs"):
    val directory = Files.createTempDirectory(temporaryDirectoryPrefix)
    try
      val schemaBytes =
        """{"requests":[{"requestType":"GetVersion","description":"Version info.","requestFields":[],"responseFields":[]}],"events":[]}"""
          .getBytes(UTF_8)
      val input  = directory.resolve(schemaFile)
      val _      = Files.write(input, schemaBytes)
      val config = directory.resolve(overridesFile)
      // Documentation-only override keys are tolerated without naming a schema field.
      val _      = Files.writeString(config, """{"numberPolicy":"documentation only","nullableFields":[]}""")
      val digest = MessageDigest.getInstance("SHA-256").digest(schemaBytes).map(byte => f"${byte & 0xff}%02x").mkString
      val pinned = directory.resolve(provenanceFile)
      val _      = Files.writeString(
        pinned,
        s"""{"repository":"https://example.com/obs","revision":"abc123","sha256":"$digest"}""",
      )
      val first  = directory.resolve("first")
      val second = directory.resolve("second")
      Generate.main(args = Array(input.toString, first.toString, config.toString, pinned.toString))
      Generate.main(args = Array(input.toString, second.toString, config.toString, pinned.toString))
      assertEquals(snapshot(directory = first), snapshot(directory = second))
      assert(snapshot(directory = first).contains(eventDispatchPath))
      assert(snapshot(directory = first)(eventDispatchPath).contains(s"sha256: $digest"))
      assert(
        snapshot(directory = first)("requests/GetVersion.scala")
          .contains("https://example.com/obs/blob/abc123/docs/generated/protocol.md#getversion")
      )
      intercept[IllegalArgumentException](Generate.main(args = Array.empty))
    finally deleteRecursively(directory = directory)

  test("entrypoint failures name the offending file"):
    val directory = Files.createTempDirectory(temporaryDirectoryPrefix)
    try
      val missing    = directory.resolve("missing.json")
      val unreadable = intercept[IllegalArgumentException]:
        Generate.main(args =
          Array(missing.toString, directory.resolve("out").toString, missing.toString, missing.toString)
        )
      assert(unreadable.getMessage.contains("missing.json"))
      val malformed  = directory.resolve("malformed.json")
      val _          = Files.writeString(malformed, """{"requests":""")
      val unparsable = intercept[IllegalArgumentException]:
        Generate.main(
          args = Array(malformed.toString, directory.resolve("out").toString, malformed.toString, malformed.toString)
        )
      assert(unparsable.getMessage.contains("malformed.json"))
    finally deleteRecursively(directory = directory)

  test("entrypoint rejects schema and provenance checksum drift"):
    val directory = Files.createTempDirectory(temporaryDirectoryPrefix)
    try
      val input  = directory.resolve(schemaFile)
      val _      = Files.writeString(input, """{"requests":[],"events":[]}""")
      val config = directory.resolve(overridesFile)
      val _      = Files.writeString(config, """{"nullableFields":[]}""")
      val pinned = directory.resolve(provenanceFile)
      val _      =
        Files.writeString(pinned, """{"repository":"https://example.com/obs","revision":"abc123","sha256":"00"}""")
      val error = intercept[IllegalArgumentException]:
        Generate.main(args = Array(input.toString, directory.resolve("out").toString, config.toString, pinned.toString))
      assert(error.getMessage.contains("does not match"))
    finally deleteRecursively(directory = directory)

  test("numeric masks and string enum generation preserve unknown-value representation"):
    val enums = List(
      SchemaEnum(
        enumType        = "Mask",
        enumIdentifiers = List(
          SchemaEnumEntry(enumIdentifier = "One", enumValue = SchemaEnumValue(value = firstBitExpression)),
          SchemaEnumEntry(
            enumIdentifier = "Two",
            enumValue      = SchemaEnumValue(value = "(1 << 1)"),
            description    = "Bitmask member.",
          ),
          SchemaEnumEntry(enumIdentifier = "All", enumValue = SchemaEnumValue(value = combinedBitsExpression)),
        ),
      ),
      SchemaEnum(
        enumType        = "State",
        enumIdentifiers =
          List(SchemaEnumEntry(enumIdentifier = "Started", enumValue = SchemaEnumValue(value = "OBS_STARTED"))),
      ),
      SchemaEnum(
        enumType        = "Status",
        enumIdentifiers = List(SchemaEnumEntry(enumIdentifier = "Success", enumValue = SchemaEnumValue(value = "100"))),
      ),
    )
    val generated =
      Generate.generate(schema = schema.copy(enums = enums), overrides = overrides, provenance = provenance).toMap
    assert(generated(maskEnumPath).contains("(`One`.value | `Two`.value)"))
    assert(generated(maskEnumPath).contains("/** Bitmask member. */"))
    assert(generated("enums/State.scala").contains("value: String"))
    assert(generated("enums/Status.scala").contains("value: Long"))

  test("mask identifiers are qualified and backticked regardless of the combining operator"):
    val enums = List(
      SchemaEnum(
        enumType        = "Combo",
        enumIdentifiers = List(
          SchemaEnumEntry(enumIdentifier = "One", enumValue    = SchemaEnumValue(value = firstBitExpression)),
          SchemaEnumEntry(enumIdentifier = "Type", enumValue   = SchemaEnumValue(value = "(1 << 1)")),
          SchemaEnumEntry(enumIdentifier = "Both", enumValue   = SchemaEnumValue(value = "(One & Type)")),
          SchemaEnumEntry(enumIdentifier = "Single", enumValue = SchemaEnumValue(value = "(One)")),
        ),
      )
    )
    val generated =
      Generate.generate(schema = schema.copy(enums = enums), overrides = overrides, provenance = provenance).toMap
    assert(generated("enums/Combo.scala").contains("Combo((`One`.value & `Type`.value))"))
    assert(generated("enums/Combo.scala").contains("Combo((`One`.value))"))

  test("bitmasks with hex literals, suffixed numerics, or unknown identifiers fail naming the enum and mask"):
    val hex = SchemaEnum(
      enumType        = "Hexed",
      enumIdentifiers = List(SchemaEnumEntry(enumIdentifier = "Bits", enumValue = SchemaEnumValue(value = "(0x1F)"))),
    )
    val hexError = intercept[IllegalArgumentException]:
      Generate.generate(schema = schema.copy(enums = List(hex)), overrides = overrides, provenance = provenance)
    assert(hexError.getMessage.contains("Hexed"))
    assert(hexError.getMessage.contains("(0x1F)"))
    val suffixed = SchemaEnum(
      enumType        = "Suffixed",
      enumIdentifiers = List(SchemaEnumEntry(enumIdentifier = "Bits", enumValue = SchemaEnumValue(value = "(1L << 0)"))),
    )
    val suffixedError = intercept[IllegalArgumentException]:
      Generate.generate(schema = schema.copy(enums = List(suffixed)), overrides = overrides, provenance = provenance)
    assert(suffixedError.getMessage.contains("Suffixed"))
    assert(suffixedError.getMessage.contains("(1L << 0)"))
    val unknown = SchemaEnum(
      enumType        = "UnknownRef",
      enumIdentifiers = List(
        SchemaEnumEntry(enumIdentifier = "One", enumValue = SchemaEnumValue(value = firstBitExpression)),
        SchemaEnumEntry(enumIdentifier = "All", enumValue = SchemaEnumValue(value = combinedBitsExpression)),
      ),
    )
    val unknownError = intercept[IllegalArgumentException]:
      Generate.generate(schema = schema.copy(enums = List(unknown)), overrides = overrides, provenance = provenance)
    assert(unknownError.getMessage.contains("UnknownRef"))
    assert(unknownError.getMessage.contains(combinedBitsExpression))
    assert(unknownError.getMessage.contains("Two"))

  test("a schema field named after a renamed payload field fails generation"):
    val request = Schema(
      requests = List(
        SchemaRequest(
          requestType   = "Collision",
          requestFields = List(
            SchemaField(valueName = "requestType", valueType        = "String"),
            SchemaField(valueName = "payloadRequestType", valueType = "String"),
          ),
          responseFields = Nil,
        )
      ),
      events = Nil,
    )
    val requestError = intercept[IllegalArgumentException](
      Generate.generate(schema = request, overrides = Overrides(nullableFields = Nil), provenance = provenance)
    )
    assert(requestError.getMessage.contains("payloadRequestType"))
    val event = Schema(
      requests = Nil,
      events   = List(
        SchemaEvent(
          eventType  = "Collision",
          dataFields = List(
            SchemaField(valueName = "eventType", valueType        = "String"),
            SchemaField(valueName = "payloadEventType", valueType = "String"),
          ),
        )
      ),
    )
    val eventError = intercept[IllegalArgumentException](
      Generate.generate(schema = event, overrides = Overrides(nullableFields = Nil), provenance = provenance)
    )
    assert(eventError.getMessage.contains("payloadEventType"))

  test("the response round-trip dispatch is generated package-private while request decode stays public"):
    val generated = Generate.generate(schema = schema, overrides = overrides, provenance = provenance).toMap
    val catalog   = generated(catalogPath)
    assert(catalog.contains("private[protocol] def roundTripResponse("))
    assert(!catalog.contains("\n  def roundTripResponse("))
    assert(catalog.contains("\n  def decodeRequest("))
    assert(!catalog.contains("private[protocol] def decodeRequest("))

  test("genuinely mixed enum values are rejected instead of guessed"):
    val mixed = SchemaEnum(
      enumType        = "Mixed",
      enumIdentifiers = List(
        SchemaEnumEntry(enumIdentifier = "Numeric", enumValue = SchemaEnumValue(value = "100")),
        SchemaEnumEntry(enumIdentifier = "Text", enumValue    = SchemaEnumValue(value = "OBS_TEXT")),
      ),
    )
    val error = intercept[IllegalArgumentException]:
      Generate.generate(schema = schema.copy(enums = List(mixed)), overrides = overrides, provenance = provenance)
    assert(error.getMessage.contains("Mixed"))
    assert(error.getMessage.contains("OBS_TEXT"))

  test("empty enum values from JSON null fail with the enum name"):
    val blank = SchemaEnum(
      enumType        = "Blank",
      enumIdentifiers = List(SchemaEnumEntry(enumIdentifier = "Broken", enumValue = SchemaEnumValue(value = ""))),
    )
    val error = intercept[IllegalArgumentException]:
      Generate.generate(schema = schema.copy(enums = List(blank)), overrides = overrides, provenance = provenance)
    assert(error.getMessage.contains("Blank"))

  test("enum string values are escaped in generated literals"):
    val tricky = SchemaEnum(
      enumType        = "Tricky",
      enumIdentifiers =
        List(SchemaEnumEntry(enumIdentifier = "Escaped", enumValue = SchemaEnumValue(value = "OBS_\"\\\n"))),
    )
    val generated = Generate
      .generate(schema = schema.copy(enums = List(tricky)), overrides = overrides, provenance = provenance)
      .toMap
    assert(generated("enums/Tricky.scala").contains("Tricky(\"OBS_\\\"\\\\\\u000a\")"))

  test("generated documentation carries description, versions, deprecation and the pinned upstream link"):
    val documented = SchemaRequest(
      requestType    = "Documented",
      requestFields  = Nil,
      responseFields = Nil,
      initialVersion = "5.0.0",
      description    = "First line.\nSecond line.",
      rpcVersion     = "1",
      deprecated     = true,
    )
    val generated =
      Generate
        .generate(
          schema     = schema.copy(requests = schema.requests :+ documented),
          overrides  = overrides,
          provenance = provenance,
        )
        .toMap
    val source = generated("requests/Documented.scala")
    assert(source.contains("/** First line. Second line."))
    assert(source.contains("(initial version 5.0.0, RPC version 1)."))
    assert(source.contains("Deprecated upstream."))
    assert(
      source.contains(
        s"${provenance.upstreamDocs}#documented"
      )
    )
    assert(source.contains(s"sha256: ${provenance.sha256}"))
    assert(generated(zedRequestPath).contains("/** Generated binding for Zed."))
    assert(!generated(zedRequestPath).contains("initial version"))

  test("schema enum codecs accept upstream mixed strings and numeric values"):
    import com.github.plokhotnyuk.jsoniter_scala.core.*
    assertEquals(readFromString[SchemaEnumValue]("100"), SchemaEnumValue(value = "100"))
    assertEquals(readFromString[SchemaEnumValue]("\"(1 << 2)\""), SchemaEnumValue(value = "(1 << 2)"))
    assertEquals(writeToString(SchemaEnumValue(value = "future")), "\"future\"")
    assertEquals(summon[JsonValueCodec[SchemaEnumValue]].nullValue, SchemaEnumValue(value = ""))

  test("field documentation preserves descriptions, restrictions, omission semantics and escaped comments"):
    val field = SchemaField(
      valueName             = "requestType",
      valueType             = "Number",
      valueOptional         = true,
      valueDescription      = "Volume in dB. */",
      valueRestrictions     = Some(">= -100, <= 26"),
      valueOptionalBehavior = Some("Specify inputVolumeMul"),
    )
    val described =
      Schema(
        requests =
          List(SchemaRequest(requestType = "Volume", requestFields = List(field), responseFields = List(field))),
        events = List(SchemaEvent(eventType = "VolumeChanged", dataFields = List(field))),
      )
    val generated =
      Generate.generate(schema = described, overrides = Overrides(nullableFields = Nil), provenance = provenance).toMap
    val expected =
      "@param `payloadRequestType` Volume in dB. * / Restrictions: >= -100, <= 26 When omitted: Specify inputVolumeMul"
    assertEquals(generated("requests/Volume.scala").sliding(expected.length).count(_ == expected), 2)
    assert(generated("events/VolumeChanged.scala").contains(expected))

  private def snapshot(directory: Path): Map[String, String] =
    val stream = Files.walk(directory)
    try
      import scala.jdk.CollectionConverters.*
      stream
        .filter(Files.isRegularFile(_))
        .toList
        .asScala
        .map(path => directory.relativize(path).toString.replace('\\', '/') -> Files.readString(path, UTF_8))
        .toMap
    finally stream.close()

  private def deleteRecursively(directory: Path): Unit =
    val stream = Files.walk(directory)
    try
      import scala.jdk.CollectionConverters.*
      stream.toList.asScala.sortBy(_.toString).reverse.foreach(Files.delete(_))
    finally stream.close()
