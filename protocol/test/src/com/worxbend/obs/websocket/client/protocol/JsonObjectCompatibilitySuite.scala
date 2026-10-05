package com.worxbend.obs.websocket.client.protocol

import munit.FunSuite

class JsonObjectCompatibilitySuite extends FunSuite:
  test("contextual decode paths do not change JSON equality and copies preserve context"):
    val raw        = JsonObject(fields = Map("name" -> JsonValue.Str(value = "Scene")))
    val contextual = raw.at(path = "scenes[0]")
    assertEquals(contextual, raw)
    assertEquals(contextual.copy(), raw)
    assertEquals(contextual.copy(fields = Map.empty).string(name = "name").swap.toOption.get.path, "scenes[0].name")
    assertEquals(contextual.copy().int(name = "name").swap.toOption.get.path, "scenes[0].name")

  test("empty and single-key objects render canonical JSON"):
    assertEquals(JsonValue.render(value = JsonObject.empty), "{}")
    assertEquals(
      JsonValue.render(value = JsonObject(fields = Map("name" -> JsonValue.Str(value = "Scene")))),
      """{"name":"Scene"}""",
    )

  test("the explicit constructor defaults to an unqualified decode path"):
    val raw = new JsonObject(fields = Map("name" -> JsonValue.Str(value = "Scene")))()
    assertEquals(raw.string(name = "name"), Right("Scene"))
    assertEquals(raw.string(name = "missing").swap.toOption.get.path, "missing")
    assertEquals(raw, JsonObject(fields = Map("name" -> JsonValue.Str(value = "Scene"))))
