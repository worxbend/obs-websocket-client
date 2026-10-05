package com.worxbend.obs.websocket.client.protocol

import com.worxbend.obs.websocket.client.protocol.workflows.*
import com.worxbend.obs.websocket.client.protocol.requests.GetSourceScreenshotResponse
import java.nio.ByteBuffer
import java.util.Base64
import munit.FunSuite

class WorkflowSuite extends FunSuite:
  private val threeByteScreenshot: String = "data:image/png;base64,AQID"

  test("screenshot decoding retains MIME and immutable compressed bytes"):
    val screenshot = Screenshot.decode(dataUri = threeByteScreenshot).toOption.get
    assertEquals(screenshot.mediaType, "image/png")
    assertEquals(screenshot.bytes, Vector[Byte](1, 2, 3))
    assertEquals(
      Screenshot
        .fromResponse(response = GetSourceScreenshotResponse(imageData = "data:image/jpeg;base64,AQ=="))
        .toOption
        .get
        .bytes,
      Vector[Byte](1),
    )

  test("screenshot decoding rejects malformed headers without echoing data"):
    Vector("secret", ("x" * 129) + ",secret", "data:text/plain;base64,c2VjcmV0", "data:image/png,c2VjcmV0").foreach:
      value =>
        val error = Screenshot.decode(dataUri = value).swap.toOption.get
        assertEquals(error.path, "imageData")
        assert(!error.message.contains("secret"))

  test("screenshot decoding validates base64 and nonempty content"):
    assert(Screenshot.decode(dataUri = "data:image/png;base64,!").isLeft)
    assert(Screenshot.decode(dataUri = "data:image/png;base64,").isLeft)

  test("screenshot decoding enforces decoded and encoded allocation limits"):
    assert(Screenshot.decode(dataUri = threeByteScreenshot, maxBytes = 0).isLeft)
    assert(Screenshot.decode(dataUri = "data:image/png;base64,AQIDAQID", maxBytes = 1).isLeft)
    assert(Screenshot.decode(dataUri = threeByteScreenshot, maxBytes = 1).isLeft)
    assertEquals(
      Screenshot
        .fromResponse(response = GetSourceScreenshotResponse(imageData = "data:image/png;base64,AQ=="), maxBytes = 1)
        .toOption
        .get
        .bytes,
      Vector[Byte](1),
    )

  test("projector geometry matches Qt big endian 3.0 framing and inclusive rectangles"):
    val geometry =
      ProjectorGeometry.window(x = -100, y = 20, width = 640, height = 480, screen = 1, screenWidth = 1920).toOption.get
    val raw = Base64.getDecoder.decode(geometry.base64)
    assertEquals(raw.length, 66)
    val data = ByteBuffer.wrap(raw)
    assertEquals(data.getInt(), 0x01d9d0cb)
    assertEquals(data.getShort(), 3.toShort)
    assertEquals(data.getShort(), 0.toShort)
    def rectangle(): Vector[Int] = Vector.fill(4)(data.getInt())
    assertEquals(rectangle(), Vector(-100, 20, 539, 499))
    assertEquals(rectangle(), Vector(-100, 20, 539, 499))
    assertEquals(data.getInt(), 1)
    assertEquals(data.get(), 0.toByte)
    assertEquals(data.get(), 0.toByte)
    assertEquals(data.getInt(), 1920)
    assertEquals(rectangle(), Vector(-100, 20, 539, 499))

  test("projector geometry rejects invalid dimensions screens and coordinate overflow"):
    Vector((0, 1), (1, 0)).foreach: (width, height) =>
      assert(
        ProjectorGeometry.window(x = 0, y = 0, width = width, height = height, screen = 0, screenWidth = 100).isLeft
      )
    assert(ProjectorGeometry.window(x = 0, y = 0, width = 1, height = 1, screen = -1, screenWidth = 100).isLeft)
    assert(ProjectorGeometry.window(x = 0, y = 0, width = 1, height = 1, screen = 0, screenWidth = 0).isLeft)
    assert(
      ProjectorGeometry.window(x = Int.MaxValue, y = 0, width = 2, height = 1, screen = 0, screenWidth = 100).isLeft
    )
    assert(
      ProjectorGeometry.window(x = 0, y = Int.MaxValue, width = 1, height = 2, screen = 0, screenWidth = 100).isLeft
    )
    assert(
      ProjectorGeometry
        .window(x = Int.MinValue, y = Int.MinValue, width = 1, height = 1, screen = 0, screenWidth = 100)
        .isRight
    )

  test("volume units convert amplitude with 20 log10 and choose exactly one wire unit"):
    val input = InputRef.byName(value = "Mic").toOption.get
    val db    = Decibels(value = BigDecimal(-20)).toOption.get
    assertEquals(db.multiplier.value, BigDecimal("0.1"))
    assertEquals(db.multiplier.decibels.toOption.get.value, BigDecimal(-20))
    val request = db.set(input = input)
    assertEquals(request.inputVolumeDb, Field.Value(value = BigDecimal(-20)))
    assertEquals(request.inputVolumeMul, Field.Missing)
    val linear = VolumeMultiplier(value = BigDecimal(1)).toOption.get.set(input = input)
    assertEquals(linear.inputVolumeDb, Field.Missing)
    assertEquals(linear.inputVolumeMul, Field.Value(value = BigDecimal(1)))

  test("volume constructors and conversion enforce different OBS unit limits"):
    Vector(BigDecimal(-101), BigDecimal(27)).foreach(value => assert(Decibels(value = value).isLeft))
    Vector(BigDecimal(-1), BigDecimal(21)).foreach(value => assert(VolumeMultiplier(value = value).isLeft))
    assertEquals(Decibels(value = BigDecimal(-100)).toOption.get.value, BigDecimal(-100))
    assert(Decibels(value = BigDecimal(26)).toOption.get.multiplier.value < 20)
    assert(VolumeMultiplier(value = BigDecimal(0)).toOption.get.decibels.isLeft)
    assert(VolumeMultiplier(value = BigDecimal("1e-1000")).toOption.get.decibels.isLeft)
    assert(VolumeMultiplier(value = BigDecimal(20)).toOption.get.decibels.isRight)

  test("multiplier conversion saturates at the documented 26 dB ceiling at the top of its range"):
    val ceiling = VolumeMultiplier(value = BigDecimal(20)).toOption.get.decibels.toOption.get
    assertEquals(ceiling.value, BigDecimal(26))
    val justBelow = VolumeMultiplier(value = BigDecimal("19.9")).toOption.get.decibels.toOption.get.value
    assert(justBelow < BigDecimal(26))
    assert(justBelow > BigDecimal(25))
    val unity = VolumeMultiplier(value = BigDecimal(1)).toOption.get.decibels.toOption.get
    assertEquals(unity.value, BigDecimal(0))
    assert(Decibels(value = BigDecimal(26)).isRight)
    assert(Decibels(value = BigDecimal("26.01")).isLeft)

  test("browser patches use current OBS keys and overlay preserves unrelated settings"):
    val patch = BrowserInputSettings
      .remote(url = "https://example.com")
      .toOption
      .get
      .withSize(width = 1280, height = 720)
      .toOption
      .get
      .withFrameRate(fps = 60)
      .toOption
      .get
      .withCss(css = "body { color: red; }")
      .shutdownWhenHidden
      .restartWhenActive
    assertEquals(patch.toJson.fields("is_local_file"), JsonValue.Bool(value = false))
    assertEquals(patch.toJson.fields("width"), JsonValue.Num(value = BigDecimal(1280)))
    assertEquals(patch.toJson.fields("fps_custom"), JsonValue.Bool(value = true))
    val merged =
      patch.mergeInto(existing =
        JsonObject(fields =
          Map("future" -> JsonValue.Str(value = "preserved"), "width" -> JsonValue.Num(value = BigDecimal(1)))
        )
      )
    assertEquals(merged.fields("future"), JsonValue.Str(value = "preserved"))
    assertEquals(merged.fields("width"), JsonValue.Num(value = BigDecimal(1280)))
    val request = patch.set(input = InputRef.byUuid(value = "uuid").toOption.get)
    assertEquals(request.overlay, Field.Value(value = true))
    assertEquals(request.inputSettings, patch.toJson)
    assertEquals(request.inputUuid, Field.Value(value = "uuid"))

  test("browser local and empty patches provide explicit lifecycle controls"):
    val patch =
      BrowserInputSettings.local(path = "/tmp/page.html").toOption.get.keepRunningWhenHidden.keepPageWhenActive
    assertEquals(patch.toJson.fields("is_local_file"), JsonValue.Bool(value = true))
    assertEquals(patch.toJson.fields("local_file"), JsonValue.Str(value = "/tmp/page.html"))
    assertEquals(patch.toJson.fields("shutdown"), JsonValue.Bool(value = false))
    assertEquals(patch.toJson.fields("restart_when_active"), JsonValue.Bool(value = false))
    assertEquals(BrowserInputSettings.empty.toJson, JsonObject.empty)

  test("browser settings reject empty locations nonpositive dimensions and frame rates"):
    assert(BrowserInputSettings.remote(url = " ").isLeft)
    assert(BrowserInputSettings.local(path = "").isLeft)
    assert(BrowserInputSettings.empty.withSize(width = 0, height = 1).isLeft)
    assert(BrowserInputSettings.empty.withSize(width = 1, height = 0).isLeft)
    assert(BrowserInputSettings.empty.withFrameRate(fps = 0).isLeft)
