package com.worxbend.obs.websocket.client.protocol

import com.worxbend.obs.websocket.client.protocol.requests.TriggerHotkeyByKeySequence
import munit.FunSuite

class NestedFieldsSuite extends FunSuite:
  test("hotkey modifiers encode as nested JSON and validate through the real request decoder"):
    val request = TriggerHotkeyByKeySequence(keyId = Field.Value("OBS_KEY_A"), `keyModifiers.shift` = Field.Value(true))
    val expected = JsonValue.parse("""{"keyId":"OBS_KEY_A","keyModifiers":{"shift":true}}""").toOption.get
    assertEquals(request.requestData, expected)
    assertEquals(request.validate, Right(()))
    val decoded = TriggerHotkeyByKeySequence.decode(request.requestData).toOption.get
    assertEquals(decoded.`keyModifiers.shift`, Field.Value(true))
    assertEquals(decoded.`keyModifiers.alt`, Field.Missing)
    assertEquals(decoded.requestData, expected)

  test("explicit hotkey child wins conflicts while unknown nested parent fields survive"):
    val parent = JsonObject(Map("shift" -> JsonValue.Bool(false), "futureModifier" -> JsonValue.Bool(true)))
    val request =
      TriggerHotkeyByKeySequence(keyModifiers = Field.Value(parent), `keyModifiers.shift` = Field.Value(true))
    val expected = JsonValue.parse("""{"keyModifiers":{"shift":true,"futureModifier":true}}""").toOption.get
    assertEquals(request.requestData, expected)
    assertEquals(TriggerHotkeyByKeySequence.decode(request.requestData).toOption.get.requestData, expected)

  test("omitted hotkey parent stays absent and invalid parent or child is rejected"):
    assertEquals(TriggerHotkeyByKeySequence().requestData, JsonObject.empty)
    assert(
      TriggerHotkeyByKeySequence(keyModifiers = Field.Null, `keyModifiers.shift` = Field.Value(true)).validate.isLeft
    )
    assert(TriggerHotkeyByKeySequence(`keyModifiers.shift` = Field.Null).validate.isLeft)
    val wrong = JsonObject(Map("keyModifiers" -> JsonValue.Str("invalid")))
    assert(TriggerHotkeyByKeySequence.decode(wrong).isLeft)

  test("nested paths support deeper objects and required leaf decoding"):
    val data = NestedFields.encode(Map("outer.inner.leaf" -> JsonValue.Str("value"), "top" -> JsonValue.Bool(true)))
    assertEquals(NestedFields.required(data, "outer.inner.leaf", ValueCodec.string), Right("value"))
    assertEquals(NestedFields.field(data, "outer.inner.leaf", ValueCodec.string, false), Right(Field.Value("value")))
    assertEquals(NestedFields.field(data, "missing.leaf", ValueCodec.string, false), Right(Field.Missing))
    assert(NestedFields.required(data, "missing.leaf", ValueCodec.string).isLeft)
    assert(NestedFields.required(data, "outer.inner.leaf", ValueCodec.number).isLeft)
    assert(
      NestedFields
        .field(JsonObject(Map("outer" -> JsonValue.Bool(false))), "outer.leaf", ValueCodec.string, false)
        .isLeft
    )
