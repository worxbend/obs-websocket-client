package com.worxbend.obs.websocket.client.codegen

import munit.FunSuite
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.MessageDigest

class GenerateSuite extends FunSuite:
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
    assert(first.find(_._1 == "requests/Zed.scala").get._2.contains("Option[BigDecimal]"))
    assert(
      first
        .find(_._1 == "requests/Zed.scala")
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
    assert(generated("Catalog.scala").contains("requests.Nullable.minimal"))

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

  test("unknown schema types fail instead of silently generating untyped bindings"):
    val unknown = Schema(List(SchemaRequest("Unknown", List(SchemaField("x", "Mystery")), Nil)), Nil)
    val error = intercept[IllegalArgumentException](Generate.generate(unknown, Overrides(Nil), provenance))
    assert(error.getMessage.contains("Unknown.request.x"))

  test("entrypoint writes deterministic offline outputs"):
    val directory = Files.createTempDirectory("obs-codegen-test")
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
    val directory = Files.createTempDirectory("obs-codegen-test")
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
    val directory = Files.createTempDirectory("obs-codegen-test")
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
          SchemaEnumEntry("One", SchemaEnumValue("(1 << 0)")),
          SchemaEnumEntry("Two", SchemaEnumValue("(1 << 1)"), "Bitmask member."),
          SchemaEnumEntry("All", SchemaEnumValue("(One | Two)"))
        )
      ),
      SchemaEnum("State", List(SchemaEnumEntry("Started", SchemaEnumValue("OBS_STARTED")))),
      SchemaEnum("Status", List(SchemaEnumEntry("Success", SchemaEnumValue("100"))))
    )
    val generated = Generate.generate(schema.copy(enums = enums), overrides, provenance).toMap
    assert(generated("enums/Mask.scala").contains("One.value | Two.value"))
    assert(generated("enums/Mask.scala").contains("/** Bitmask member. */"))
    assert(generated("enums/State.scala").contains("value: String"))
    assert(generated("enums/Status.scala").contains("value: Long"))

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
    assert(generated("requests/Zed.scala").contains("/** Generated binding for Zed."))
    assert(!generated("requests/Zed.scala").contains("initial version"))

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
      "@param payloadRequestType Volume in dB. * / Restrictions: >= -100, <= 26 When omitted: Specify inputVolumeMul"
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
        .map(path => directory.relativize(path).toString -> Files.readString(path, UTF_8))
        .toMap
    finally stream.close()

  private def deleteRecursively(directory: Path): Unit =
    val stream = Files.walk(directory)
    try
      import scala.jdk.CollectionConverters.*
      stream.toList.asScala.sortBy(_.toString).reverse.foreach(Files.delete(_))
    finally stream.close()
