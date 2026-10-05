package com.worxbend.obs.websocket.client.protocol

import com.worxbend.obs.websocket.client.protocol.requests.TriggerHotkeyByKeySequence
import munit.FunSuite

class NestedFieldsSuite extends FunSuite:
  private val nestedLeafPath: String = "outer.inner.leaf"

  test("hotkey modifiers encode as nested JSON and validate through the real request decoder"):
    val request = TriggerHotkeyByKeySequence(
      keyId                = Field.Value(value = "OBS_KEY_A"),
      `keyModifiers.shift` = Field.Value(value = true),
    )
    val expected = JsonValue.parse(text = """{"keyId":"OBS_KEY_A","keyModifiers":{"shift":true}}""").toOption.get
    assertEquals(request.requestData, expected)
    assertEquals(request.validate, Right(()))
    val decoded = TriggerHotkeyByKeySequence.decode(data = request.requestData).toOption.get
    assertEquals(decoded.`keyModifiers.shift`, Field.Value(value = true))
    assertEquals(decoded.`keyModifiers.alt`, Field.Missing)
    assertEquals(decoded.requestData, expected)

  test("explicit hotkey child wins conflicts while unknown nested parent fields survive"):
    val parent = JsonObject(fields =
      Map("shift" -> JsonValue.Bool(value = false), "futureModifier" -> JsonValue.Bool(value = true))
    )
    val request =
      TriggerHotkeyByKeySequence(
        keyModifiers         = Field.Value(value = parent),
        `keyModifiers.shift` = Field.Value(value = true),
      )
    val expected = JsonValue.parse(text = """{"keyModifiers":{"shift":true,"futureModifier":true}}""").toOption.get
    assertEquals(request.requestData, expected)
    assertEquals(TriggerHotkeyByKeySequence.decode(data = request.requestData).toOption.get.requestData, expected)

  test("omitted hotkey parent stays absent and invalid parent or child is rejected"):
    assertEquals(TriggerHotkeyByKeySequence().requestData, JsonObject.empty)
    assert(
      TriggerHotkeyByKeySequence(
        keyModifiers         = Field.Null,
        `keyModifiers.shift` = Field.Value(value = true),
      ).validate.isLeft
    )
    assert(TriggerHotkeyByKeySequence(`keyModifiers.shift` = Field.Null).validate.isLeft)
    val wrong = JsonObject(fields = Map("keyModifiers" -> JsonValue.Str(value = "invalid")))
    assert(TriggerHotkeyByKeySequence.decode(data = wrong).isLeft)

  test("nested paths support deeper objects and required leaf decoding"):
    val data = NestedFields.encode(fields =
      Map(nestedLeafPath -> JsonValue.Str(value = "value"), "top" -> JsonValue.Bool(value = true))
    )
    assertEquals(NestedFields.required(data = data, name = nestedLeafPath, codec = ValueCodec.string), Right("value"))
    assertEquals(
      NestedFields.field(data = data, name = nestedLeafPath, codec = ValueCodec.string, nullable = false),
      Right(Field.Value(value = "value")),
    )
    assertEquals(
      NestedFields.field(data = data, name = "missing.leaf", codec = ValueCodec.string, nullable = false),
      Right(Field.Missing),
    )
    assert(NestedFields.required(data = data, name = "missing.leaf", codec = ValueCodec.string).isLeft)
    assert(NestedFields.required(data = data, name = nestedLeafPath, codec = ValueCodec.number).isLeft)
    assert(
      NestedFields
        .field(
          data     = JsonObject(fields = Map("outer" -> JsonValue.Bool(value = false))),
          name     = "outer.leaf",
          codec    = ValueCodec.string,
          nullable = false,
        )
        .isLeft
    )
