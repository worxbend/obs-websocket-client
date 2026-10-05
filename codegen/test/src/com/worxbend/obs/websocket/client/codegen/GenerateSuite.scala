package com.worxbend.obs.websocket.client.codegen

import com.worxbend.obs.websocket.client.codegen.schema.*
import munit.FunSuite
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.MessageDigest

class GenerateSuite extends FunSuite:
  private val firstBitExpression: String = "(1 << 0)"
  private val combinedBitsExpression: String = "(One | Two)"
  private val catalogPath: String = "Catalog.scala"
  private val nestedRequestPath: String = "requests/Nested.scala"
  private val zedRequestPath: String = "requests/Zed.scala"
  private val temporaryDirectoryPrefix: String = "obs-codegen-test"

  private val provenance = Provenance(
    "https://github.com/obsproject/obs-websocket",
    "0123456789abcdef0123456789abcdef01234567",
    "f" * 64
  )
  private val allFields =
    List("String", "Number", "Boolean", "Object", "Any", "Array<Object>", "Array<String>").zipWithIndex.map:
      case (tpe, index) => SchemaField(s"field$index", tpe, valueOptional = index % 2 == 0)
  private val schema = Schema(
    List(
      SchemaRequest("Zed", allFields, allFields.map(_.copy(valueOptional = false))),
      SchemaRequest("Empty", Nil, Nil),
      SchemaRequest("Vendor", List(SchemaField("requestType", "String")), Nil)
    ),
    List(SchemaEvent("Changed", allFields), SchemaEvent("EmptyEvent", Nil))
  )
  private val overrides = Overrides(List("Zed.request.field0", "Zed.response.field1"))

  test("all output templates preserve the independently captured source fixtures"):
    val directory = Files.createTempDirectory("obs-codegen-golden")
    try
      def resource(name: String): String =
        val stream = getClass.getResourceAsStream(s"/golden/$name")
        try new String(stream.readAllBytes(), UTF_8)
        finally stream.close()
      List("schema.json", "overrides.json", "provenance.json").foreach: name =>
        val _ = Files.writeString(directory.resolve(name), resource(name), UTF_8)
      val output = directory.resolve("output")
      Generate.main(
        Array(
          directory.resolve("schema.json").toString,
          output.toString,
          directory.resolve("overrides.json").toString,
          directory.resolve("provenance.json").toString
        )
      )
      val names = List(
        "requests/Example.scala",
        "requests/Empty.scala",
        "events/Changed.scala",
        "enums/Mask.scala",
        "enums/State.scala",
        "Event.scala",
        catalogPath,
        "RequestApi.scala",
        "catalog-inventory.tsv"
      )
      val expected = names.map(name => name -> resource(s"expected/$name")).toMap
      assertEquals(snapshot(output), expected)
    finally deleteRecursively(directory)

  test("category facades preserve names, optionality, selectors, and stable category ordering"):
    val categories = schema.copy(requests =
      schema.requests.map(request =>
        request.copy(category = if request.requestType == "Zed" then "scene items" else "config")
      )
    )
    val generated = Generate.generate(categories, overrides, provenance).toMap
    val api = generated("RequestApi.scala")
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
    val invalid = schema.copy(requests = List(SchemaRequest("Empty", Nil, Nil, category = "bad-name")))
    assert(
      intercept[IllegalArgumentException](Generate.generate(invalid, Overrides(), provenance)).getMessage
        .contains("category")
    )

  test("generation is deterministic regardless of catalog input order"):
    val first = Generate.generate(schema, overrides, provenance)
    val second =
      Generate.generate(
        schema.copy(requests = schema.requests.reverse, events = schema.events.reverse),
        overrides,
        provenance
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
    val nullable = Schema(List(SchemaRequest("Nullable", List(SchemaField("slot", "String")), Nil)), Nil)
    val generated = Generate.generate(nullable, Overrides(List("Nullable.request.slot")), provenance).toMap
    assert(generated("requests/Nullable.scala").contains("def minimal: Nullable = Nullable(`slot` = None)"))
    assert(generated(catalogPath).contains("requests.Nullable.minimal"))

  test("unmatched nullable overrides fail with the offending key"):
    val error = intercept[IllegalArgumentException]:
      Generate.generate(schema, Overrides(List("Zed.request.typo")), provenance)
    assert(error.getMessage.contains("Zed.request.typo"))

  test("duplicate generated names fail per kind with the offenders listed"):
    val requests = schema.copy(requests = List(SchemaRequest("Same", Nil, Nil), SchemaRequest("Same", Nil, Nil)))
    assert(
      intercept[IllegalArgumentException](Generate.generate(requests, Overrides(Nil), provenance)).getMessage
        .contains("Same")
    )
    val events = schema.copy(events = List(SchemaEvent("Same", Nil), SchemaEvent("Same", Nil)))
    assert(
      intercept[IllegalArgumentException](Generate.generate(events, Overrides(Nil), provenance)).getMessage
        .contains("Same")
    )
    val enums = schema.copy(enums =
      List(SchemaEnum("Same", List(SchemaEnumEntry("One", SchemaEnumValue("1")))), SchemaEnum("Same", Nil))
    )
    assert(
      intercept[IllegalArgumentException](Generate.generate(enums, Overrides(Nil), provenance)).getMessage
        .contains("Same")
    )

  test("duplicate field names within one payload fail with the owning field list"):
    val duplicated = SchemaField("slot", "String")
    val request = schema.copy(requests =
      List(SchemaRequest("Dupe", List(duplicated, duplicated.copy(valueType = "Number")), List(duplicated, duplicated)))
    )
    val requestError = intercept[IllegalArgumentException](Generate.generate(request, Overrides(Nil), provenance))
    assert(requestError.getMessage.contains("Dupe.request"))
    val event =
      schema.copy(events = List(SchemaEvent("Dupe", List(duplicated, duplicated.copy(valueType = "Boolean")))))
    val eventError = intercept[IllegalArgumentException](Generate.generate(event, Overrides(Nil), provenance))
    assert(eventError.getMessage.contains("Dupe.event"))

  test("duplicate enum identifiers within one enum fail with the enum name"):
    val duplicated = SchemaEnum(
      "Dupe",
      List(SchemaEnumEntry("One", SchemaEnumValue("1")), SchemaEnumEntry("One", SchemaEnumValue("2")))
    )
    val error = intercept[IllegalArgumentException]:
      Generate.generate(schema.copy(enums = List(duplicated)), overrides, provenance)
    assert(error.getMessage.contains("Dupe"))
    assert(error.getMessage.contains("One"))

  test("a request named after another request's response class fails"):
    val colliding = schema.copy(requests = schema.requests ++ List(SchemaRequest("ZedResponse", Nil, Nil)))
    val error = intercept[IllegalArgumentException](Generate.generate(colliding, overrides, provenance))
    assert(error.getMessage.contains("ZedResponse"))

  test("an empty schema still generates a compilable catalog"):
    val generated = Generate.generate(Schema(Nil, Nil), Overrides(Nil), provenance).toMap
    assert(generated(catalogPath).contains("Map.empty"))
    assert(
      !generated(catalogPath).contains(
        "Decoders: Map[String, JsonObject => Either[ProtocolError, Request[?]]] = \n"
      )
    )

  test("invalid field names fail with owner context instead of leaking into generated code"):
    val unnamed = Schema(List(SchemaRequest("Broken", List(SchemaField("", "String")), Nil)), Nil)
    val empty = intercept[IllegalArgumentException](Generate.generate(unnamed, Overrides(Nil), provenance))
    assert(empty.getMessage.contains("Broken.request"))
    val symbol = Schema(List(SchemaRequest("Broken", List(SchemaField("not-a-name", "String")), Nil)), Nil)
    val invalid = intercept[IllegalArgumentException](Generate.generate(symbol, Overrides(Nil), provenance))
    assert(invalid.getMessage.contains("Broken.request.not-a-name"))
    val ticked = Schema(List(SchemaRequest("Broken", List(SchemaField("bad`name", "String")), Nil)), Nil)
    val backtick = intercept[IllegalArgumentException](Generate.generate(ticked, Overrides(Nil), provenance))
    assert(backtick.getMessage.contains("Broken.request.bad`name"))
    val reserved = Schema(List(SchemaRequest("Broken", Nil, List(SchemaField("toJson", "String")))), Nil)
    val collision = intercept[IllegalArgumentException](Generate.generate(reserved, Overrides(Nil), provenance))
    assert(collision.getMessage.contains("Broken.response.toJson"))

  test("dotted schema names stay legal backticked identifiers"):
    val dotted = Schema(List(SchemaRequest("Nested", List(SchemaField("keyModifiers.shift", "Boolean")), Nil)), Nil)
    val generated = Generate.generate(dotted, Overrides(Nil), provenance).toMap
    assert(generated(nestedRequestPath).contains("`keyModifiers.shift`: Boolean"))
    assert(generated(nestedRequestPath).contains("NestedFields.encode("))
    assert(
      generated(nestedRequestPath).contains(
        "NestedFields.required(data, \"keyModifiers.shift\", ValueCodec.boolean)"
      )
    )

  test("optional dotted fields decode through the parent object with omission semantics"):
    val nested = Schema(
      List(SchemaRequest("Nested", List(SchemaField("parent.child", "Boolean", valueOptional = true)), Nil)),
      Nil
    )
    val generated = Generate.generate(nested, Overrides(), provenance).toMap
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
      Overrides(Nil)
    )

  test("inventory rows collapse restrictions onto one line and include event field restrictions"):
    val field = SchemaField("slot", "String", valueRestrictions = Some(">= 0,\n\t<= 100"))
    val inventoried = Schema(List(SchemaRequest("Ranged", List(field), Nil)), List(SchemaEvent("Ranged", List(field))))
    val generated = Generate.generate(inventoried, Overrides(Nil), provenance).toMap
    val rows = generated("catalog-inventory.tsv").linesIterator.filter(_.contains("Ranged")).toList
    assertEquals(rows.size, 2)
    assert(rows.forall(_.endsWith("\t>= 0, <= 100")))

  test("unknown schema types fail instead of silently generating untyped bindings"):
    val unknown = Schema(List(SchemaRequest("Unknown", List(SchemaField("x", "Mystery")), Nil)), Nil)
    val error = intercept[IllegalArgumentException](Generate.generate(unknown, Overrides(Nil), provenance))
    assert(error.getMessage.contains("Unknown.request.x"))

  test("entrypoint writes deterministic offline outputs"):
    val directory = Files.createTempDirectory(temporaryDirectoryPrefix)
    try
      val schemaBytes =
        """{"requests":[{"requestType":"GetVersion","description":"Version info.","requestFields":[],"responseFields":[]}],"events":[]}"""
          .getBytes(UTF_8)
      val input = directory.resolve("schema.json")
      val _ = Files.write(input, schemaBytes)
      val config = directory.resolve("overrides.json")
      // Documentation-only override keys are tolerated without naming a schema field.
      val _ = Files.writeString(config, """{"numberPolicy":"documentation only","nullableFields":[]}""")
      val digest = MessageDigest.getInstance("SHA-256").digest(schemaBytes).map(byte => f"${byte & 0xff}%02x").mkString
      val pinned = directory.resolve("provenance.json")
      val _ = Files.writeString(
        pinned,
        s"""{"repository":"https://example.com/obs","revision":"abc123","sha256":"$digest"}"""
      )
      val first = directory.resolve("first")
      val second = directory.resolve("second")
      Generate.main(Array(input.toString, first.toString, config.toString, pinned.toString))
      Generate.main(Array(input.toString, second.toString, config.toString, pinned.toString))
      assertEquals(snapshot(first), snapshot(second))
      assert(snapshot(first).contains("Event.scala"))
      assert(snapshot(first)("Event.scala").contains(s"sha256: $digest"))
      assert(
        snapshot(first)("requests/GetVersion.scala")
          .contains("https://example.com/obs/blob/abc123/docs/generated/protocol.md#getversion")
      )
      intercept[IllegalArgumentException](Generate.main(Array.empty))
    finally deleteRecursively(directory)

  test("entrypoint failures name the offending file"):
    val directory = Files.createTempDirectory(temporaryDirectoryPrefix)
    try
      val missing = directory.resolve("missing.json")
      val unreadable = intercept[IllegalArgumentException]:
        Generate.main(Array(missing.toString, directory.resolve("out").toString, missing.toString, missing.toString))
      assert(unreadable.getMessage.contains("missing.json"))
      val malformed = directory.resolve("malformed.json")
      val _ = Files.writeString(malformed, """{"requests":""")
      val unparsable = intercept[IllegalArgumentException]:
        Generate.main(
          Array(malformed.toString, directory.resolve("out").toString, malformed.toString, malformed.toString)
        )
      assert(unparsable.getMessage.contains("malformed.json"))
    finally deleteRecursively(directory)

  test("entrypoint rejects schema and provenance checksum drift"):
    val directory = Files.createTempDirectory(temporaryDirectoryPrefix)
    try
      val input = directory.resolve("schema.json")
      val _ = Files.writeString(input, """{"requests":[],"events":[]}""")
      val config = directory.resolve("overrides.json")
      val _ = Files.writeString(config, """{"nullableFields":[]}""")
      val pinned = directory.resolve("provenance.json")
      val _ =
        Files.writeString(pinned, """{"repository":"https://example.com/obs","revision":"abc123","sha256":"00"}""")
      val error = intercept[IllegalArgumentException]:
        Generate.main(Array(input.toString, directory.resolve("out").toString, config.toString, pinned.toString))
      assert(error.getMessage.contains("does not match"))
    finally deleteRecursively(directory)

  test("numeric masks and string enum generation preserve unknown-value representation"):
    val enums = List(
      SchemaEnum(
        "Mask",
        List(
          SchemaEnumEntry("One", SchemaEnumValue(firstBitExpression)),
          SchemaEnumEntry("Two", SchemaEnumValue("(1 << 1)"), "Bitmask member."),
          SchemaEnumEntry("All", SchemaEnumValue(combinedBitsExpression))
        )
      ),
      SchemaEnum("State", List(SchemaEnumEntry("Started", SchemaEnumValue("OBS_STARTED")))),
      SchemaEnum("Status", List(SchemaEnumEntry("Success", SchemaEnumValue("100"))))
    )
    val generated = Generate.generate(schema.copy(enums = enums), overrides, provenance).toMap
    assert(generated("enums/Mask.scala").contains("(`One`.value | `Two`.value)"))
    assert(generated("enums/Mask.scala").contains("/** Bitmask member. */"))
    assert(generated("enums/State.scala").contains("value: String"))
    assert(generated("enums/Status.scala").contains("value: Long"))

  test("mask identifiers are qualified and backticked regardless of the combining operator"):
    val enums = List(
      SchemaEnum(
        "Combo",
        List(
          SchemaEnumEntry("One", SchemaEnumValue(firstBitExpression)),
          SchemaEnumEntry("Type", SchemaEnumValue("(1 << 1)")),
          SchemaEnumEntry("Both", SchemaEnumValue("(One & Type)")),
          SchemaEnumEntry("Single", SchemaEnumValue("(One)"))
        )
      )
    )
    val generated = Generate.generate(schema.copy(enums = enums), overrides, provenance).toMap
    assert(generated("enums/Combo.scala").contains("Combo((`One`.value & `Type`.value))"))
    assert(generated("enums/Combo.scala").contains("Combo((`One`.value))"))

  test("bitmasks with hex literals, suffixed numerics, or unknown identifiers fail naming the enum and mask"):
    val hex = SchemaEnum("Hexed", List(SchemaEnumEntry("Bits", SchemaEnumValue("(0x1F)"))))
    val hexError = intercept[IllegalArgumentException]:
      Generate.generate(schema.copy(enums = List(hex)), overrides, provenance)
    assert(hexError.getMessage.contains("Hexed"))
    assert(hexError.getMessage.contains("(0x1F)"))
    val suffixed = SchemaEnum("Suffixed", List(SchemaEnumEntry("Bits", SchemaEnumValue("(1L << 0)"))))
    val suffixedError = intercept[IllegalArgumentException]:
      Generate.generate(schema.copy(enums = List(suffixed)), overrides, provenance)
    assert(suffixedError.getMessage.contains("Suffixed"))
    assert(suffixedError.getMessage.contains("(1L << 0)"))
    val unknown = SchemaEnum(
      "UnknownRef",
      List(
        SchemaEnumEntry("One", SchemaEnumValue(firstBitExpression)),
        SchemaEnumEntry("All", SchemaEnumValue(combinedBitsExpression))
      )
    )
    val unknownError = intercept[IllegalArgumentException]:
      Generate.generate(schema.copy(enums = List(unknown)), overrides, provenance)
    assert(unknownError.getMessage.contains("UnknownRef"))
    assert(unknownError.getMessage.contains(combinedBitsExpression))
    assert(unknownError.getMessage.contains("Two"))

  test("a schema field named after a renamed payload field fails generation"):
    val request = Schema(
      List(
        SchemaRequest(
          "Collision",
          List(SchemaField("requestType", "String"), SchemaField("payloadRequestType", "String")),
          Nil
        )
      ),
      Nil
    )
    val requestError = intercept[IllegalArgumentException](Generate.generate(request, Overrides(Nil), provenance))
    assert(requestError.getMessage.contains("payloadRequestType"))
    val event = Schema(
      Nil,
      List(
        SchemaEvent("Collision", List(SchemaField("eventType", "String"), SchemaField("payloadEventType", "String")))
      )
    )
    val eventError = intercept[IllegalArgumentException](Generate.generate(event, Overrides(Nil), provenance))
    assert(eventError.getMessage.contains("payloadEventType"))

  test("the response round-trip dispatch is generated package-private while request decode stays public"):
    val generated = Generate.generate(schema, overrides, provenance).toMap
    val catalog = generated(catalogPath)
    assert(catalog.contains("private[protocol] def roundTripResponse("))
    assert(!catalog.contains("\n  def roundTripResponse("))
    assert(catalog.contains("\n  def decodeRequest("))
    assert(!catalog.contains("private[protocol] def decodeRequest("))

  test("genuinely mixed enum values are rejected instead of guessed"):
    val mixed = SchemaEnum(
      "Mixed",
      List(SchemaEnumEntry("Numeric", SchemaEnumValue("100")), SchemaEnumEntry("Text", SchemaEnumValue("OBS_TEXT")))
    )
    val error = intercept[IllegalArgumentException]:
      Generate.generate(schema.copy(enums = List(mixed)), overrides, provenance)
    assert(error.getMessage.contains("Mixed"))
    assert(error.getMessage.contains("OBS_TEXT"))

  test("empty enum values from JSON null fail with the enum name"):
    val blank = SchemaEnum("Blank", List(SchemaEnumEntry("Broken", SchemaEnumValue(""))))
    val error = intercept[IllegalArgumentException]:
      Generate.generate(schema.copy(enums = List(blank)), overrides, provenance)
    assert(error.getMessage.contains("Blank"))

  test("enum string values are escaped in generated literals"):
    val tricky = SchemaEnum(
      "Tricky",
      List(SchemaEnumEntry("Escaped", SchemaEnumValue("OBS_\"\\\n")))
    )
    val generated = Generate.generate(schema.copy(enums = List(tricky)), overrides, provenance).toMap
    assert(generated("enums/Tricky.scala").contains("Tricky(\"OBS_\\\"\\\\\\u000a\")"))

  test("generated documentation carries description, versions, deprecation and the pinned upstream link"):
    val documented = SchemaRequest(
      "Documented",
      Nil,
      Nil,
      initialVersion = "5.0.0",
      description = "First line.\nSecond line.",
      rpcVersion = "1",
      deprecated = true
    )
    val generated =
      Generate.generate(schema.copy(requests = schema.requests :+ documented), overrides, provenance).toMap
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
    assertEquals(readFromString[SchemaEnumValue]("100"), SchemaEnumValue("100"))
    assertEquals(readFromString[SchemaEnumValue]("\"(1 << 2)\""), SchemaEnumValue("(1 << 2)"))
    assertEquals(writeToString(SchemaEnumValue("future")), "\"future\"")
    assertEquals(summon[JsonValueCodec[SchemaEnumValue]].nullValue, SchemaEnumValue(""))

  test("field documentation preserves descriptions, restrictions, omission semantics and escaped comments"):
    val field = SchemaField(
      "requestType",
      "Number",
      valueOptional = true,
      valueDescription = "Volume in dB. */",
      valueRestrictions = Some(">= -100, <= 26"),
      valueOptionalBehavior = Some("Specify inputVolumeMul")
    )
    val described =
      Schema(List(SchemaRequest("Volume", List(field), List(field))), List(SchemaEvent("VolumeChanged", List(field))))
    val generated = Generate.generate(described, Overrides(Nil), provenance).toMap
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
