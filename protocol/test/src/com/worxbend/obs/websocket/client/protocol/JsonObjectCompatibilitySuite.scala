package com.worxbend.obs.websocket.client.protocol

import munit.FunSuite

class JsonObjectCompatibilitySuite extends FunSuite:
  test("contextual decode paths do not change JSON equality and copies preserve context"):
    val raw = JsonObject(Map("name" -> JsonValue.Str("Scene")))
    val contextual = raw.at("scenes[0]")
    assertEquals(contextual, raw)
    assertEquals(contextual.copy(), raw)
    assertEquals(contextual.copy(fields = Map.empty).string("name").swap.toOption.get.path, "scenes[0].name")
    assertEquals(contextual.copy().int("name").swap.toOption.get.path, "scenes[0].name")

  test("empty and single-key objects render canonical JSON"):
    assertEquals(JsonValue.render(JsonObject.empty), "{}")
    assertEquals(JsonValue.render(JsonObject(Map("name" -> JsonValue.Str("Scene")))), """{"name":"Scene"}""")

  test("the explicit constructor defaults to an unqualified decode path"):
    val raw = new JsonObject(Map("name" -> JsonValue.Str("Scene")))()
    assertEquals(raw.string("name"), Right("Scene"))
    assertEquals(raw.string("missing").swap.toOption.get.path, "missing")
    assertEquals(raw, JsonObject(Map("name" -> JsonValue.Str("Scene"))))
