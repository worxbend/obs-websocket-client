package com.worxbend.obs.websocket.client.protocol

import com.worxbend.obs.websocket.client.protocol.models.*
import munit.FunSuite
import java.nio.charset.StandardCharsets.UTF_8

class ObjectModelSuite extends FunSuite:
  private val sceneId: String = "scene-id"

  private def resource(name: String): JsonValue =
    val stream = getClass.getResourceAsStream(name)
    try JsonValue.parse(text = String(stream.readAllBytes(), UTF_8)).toOption.get
    finally stream.close()

  private val fixtures = resource(name = "/model-fixtures.json").asInstanceOf[JsonObject]
  private val catalog  =
    resource(name = "/catalog-fixtures.json").asInstanceOf[JsonValue.Arr].value.map(_.asInstanceOf[JsonObject])
  private val codecs: Vector[(String, ValueCodec[? <: ObjectModel])] = Vector(
    "Scene"              -> Scene.codec,
    "Input"              -> Input.codec,
    "SceneItem"          -> SceneItem.codec,
    "SceneItemTransform" -> SceneItemTransform.codec,
    "Monitor"            -> Monitor.codec,
    "Filter"             -> Filter.codec,
    "OutputFlags"        -> OutputFlags.codec,
    "Output"             -> Output.codec,
    "Transition"         -> Transition.codec,
    "PropertyItem"       -> PropertyItem.codec,
    "InputVolumeMeter"   -> InputVolumeMeter.codec,
    "Canvas"             -> Canvas.codec,
  )

  codecs.foreach: (name, existentialCodec) =>
    val codec   = existentialCodec.asInstanceOf[ValueCodec[ObjectModel]]
    val fixture = fixtures.obj(name = name).toOption.get
    val valid   = fixture.obj(name = "valid").toOption.get
    val fields  = fixture.array(name = "fields").toOption.get.map(_.asInstanceOf[JsonObject])
    test(s"$name view validates fields and preserves the complete future payload"):
      val extended = valid.copy(fields = valid.fields.updated("futureSetting", JsonValue.Str(value = "preserved")))
      val model    = codec.decode(value = extended, path = "payload").toOption.get
      assertEquals(model.raw, extended)
      assertEquals(model.toJson, extended)
      assertEquals(codec.encode(value = model), extended)
      fields.foreach: field =>
        val getter = model.getClass.getMethod(field.string(name = "name").toOption.get)
        assert(getter.invoke(model) != null)
      assertEquals(
        codec.decode(value = JsonValue.Bool(value = false), path = "payload"),
        Left(ProtocolError(path = "payload", message = "Expected object")),
      )
    fields.foreach: field =>
      val key      = field.string(name = "name").toOption.get
      val optional = field.boolean(name = "optional").toOption.get
      test(s"$name.$key missing field compatibility"):
        val decoded = codec.decode(value = valid.copy(fields = valid.fields - key), path = "payload")
        assertEquals(decoded.isRight, optional)
        if optional then
          assertEquals(decoded.toOption.get.getClass.getMethod(key).invoke(decoded.toOption.get), Field.Missing)
        else assertEquals(decoded.swap.toOption.get.path, s"payload.$key")
      test(s"$name.$key explicit null"):
        val decoded =
          codec.decode(value = valid.copy(fields = valid.fields.updated(key, JsonValue.Null)), path = "payload")
        assertEquals(decoded.isRight, optional || field.boolean(name = "any").toOption.get)
        if optional then
          assertEquals(decoded.toOption.get.getClass.getMethod(key).invoke(decoded.toOption.get), Field.Null)
      if !field.boolean(name = "any").toOption.get then
        test(s"$name.$key malformed value is never treated as absent"):
          val wrong = JsonValue.Arr(value = Vector(JsonValue.Str(value = "wrong")))
          assert(codec.decode(value = valid.copy(fields = valid.fields.updated(key, wrong)), path = "payload").isLeft)

  test("nested scene-item transform errors include the owning array index and field path"):
    val item      = fixtures.obj(name = "SceneItem").toOption.get.obj(name = "valid").toOption.get
    val malformed = item.copy(fields = item.fields.updated("sceneItemTransform", JsonObject.empty))
    val decoded   = ValueCodec
      .array(element = SceneItem.codec)
      .decode(value = JsonValue.Arr(value = Vector(item, malformed)), path = "sceneItems")
    assertEquals(decoded.swap.toOption.get.path, "sceneItems[1].sceneItemTransform.alignment")

  test("synthetic pre-UUID and current scene payloads decode without claiming live compatibility"):
    val old = JsonObject(fields =
      Map("sceneName" -> JsonValue.Str(value = "Main"), "sceneIndex" -> JsonValue.Num(value = BigDecimal(0)))
    )
    val modern = old.copy(fields = old.fields.updated("sceneUuid", JsonValue.Str(value = "server-assigned-identifier")))
    assertEquals(Scene.decode(data = old).toOption.get.sceneUuid, Field.Missing)
    assertEquals(Scene.decode(data = modern).toOption.get.sceneUuid, Field.Value(value = "server-assigned-identifier"))

  test("typed payload extensions cover every modeled response and event while retaining the raw API"):
    val views = Vector(
      ("GetCanvasListResponse", "canvases", "Canvas", true),
      ("GetSceneListResponse", "scenes", "Scene", true),
      ("GetInputListResponse", "inputs", "Input", true),
      ("GetSceneItemListResponse", "sceneItems", "SceneItem", true),
      ("GetGroupSceneItemListResponse", "sceneItems", "SceneItem", true),
      ("GetSceneItemTransformResponse", "sceneItemTransform", "SceneItemTransform", false),
      ("GetMonitorListResponse", "monitors", "Monitor", true),
      ("GetSourceFilterListResponse", "filters", "Filter", true),
      ("GetOutputListResponse", "outputs", "Output", true),
      ("GetSceneTransitionListResponse", "transitions", "Transition", true),
      ("GetInputPropertiesListPropertyItemsResponse", "propertyItems", "PropertyItem", true),
      ("InputVolumeMeters", "inputs", "InputVolumeMeter", true),
      ("SceneItemTransformChanged", "sceneItemTransform", "SceneItemTransform", false),
      ("SceneListChanged", "scenes", "Scene", true),
    )
    views.foreach: (owner, field, model, array) =>
      val response = owner.endsWith("Response")
      val name     = owner.stripSuffix("Response")
      val fixture  = catalog
        .find(f =>
          f.string(name = "kind")
            .contains(if response then "response" else "event") && f.string(name = "name").contains(name)
        )
        .get
      val raw     = fixtures.obj(name = model).toOption.get.obj(name = "valid").toOption.get
      val value   = if array then JsonValue.Arr(value = Vector(raw)) else raw
      val data    = fixture.obj(name = "valid").toOption.get
      val payload = data.copy(fields = data.fields.updated(field, value))
      val decoded = if response then
        val requestFixture =
          catalog.find(f => f.string(name = "kind").contains("request") && f.string(name = "name").contains(name)).get
        Catalog
          .decodeRequest(name = name, data = requestFixture.obj(name = "valid").toOption.get)
          .toOption
          .get
          .decodeResponse(data = payload)
          .toOption
          .get
      else Event.decode(eventType = name, data = payload).toOption.get
      val method = TypedPayloads.getClass.getDeclaredMethods
        .find(m => m.getParameterCount == 1 && m.getParameterTypes.head.getSimpleName == owner)
        .get
      val result = method.invoke(TypedPayloads, decoded.asInstanceOf[Object]).asInstanceOf[Either[ProtocolError, Any]]
      val projected = if array then result.toOption.get.asInstanceOf[Vector[ObjectModel]].head
      else result.toOption.get.asInstanceOf[ObjectModel]
      assertEquals(projected.raw, raw)

  test("name and UUID references select exactly one field and retain names verbatim"):
    assert(SceneRef.byName(value = "  ").isLeft)
    assert(SceneRef.byUuid(value = "").isLeft)
    assert(InputRef.byName(value = "").isLeft)
    assert(InputRef.byUuid(value = " \t ").isLeft)
    val scene = SceneRef.byName(value = " Main ").toOption.get
    assertEquals(scene.name, Field.Value(value = " Main "))
    assertEquals(scene.uuid, Field.Missing)
    val sceneUuid = SceneRef.byUuid(value = sceneId).toOption.get
    assertEquals(sceneUuid.name, Field.Missing)
    assertEquals(sceneUuid.uuid, Field.Value(value = sceneId))
    val input = InputRef.byName(value = "Microphone").toOption.get
    assertEquals(input.name, Field.Value(value = "Microphone"))
    assertEquals(input.uuid, Field.Missing)
    val inputUuid = InputRef.byUuid(value = "input-id").toOption.get
    assertEquals(inputUuid.name, Field.Missing)
    assertEquals(inputUuid.uuid, Field.Value(value = "input-id"))

  test("scene-item identifiers reject fractional and negative numbers without narrowing"):
    assert(SceneItemId.from(value = BigDecimal(-1)).isLeft)
    assert(SceneItemId.from(value = BigDecimal("1.5")).isLeft)
    assertEquals(SceneItemId.from(value = BigDecimal(0)).toOption.get.value, BigDecimal(0))
    val large = BigDecimal("100000000000000000000")
    assertEquals(SceneItemId.from(value = large).toOption.get.value, large)

  test("validated references build requests without conflicting name and UUID selectors"):
    val scene         = SceneRef.byUuid(value = sceneId).toOption.get
    val sceneRequests = Vector(
      scene.setProgram(),
      scene.setPreview(),
      scene.items,
      scene.findItem(sourceName = "Camera"),
      scene.findItem(sourceName = "Camera", searchOffset = Field.Value(value = BigDecimal(1))),
    )
    sceneRequests.foreach: request =>
      assertEquals(request.requestData.fields.get("sceneUuid"), Some(JsonValue.Str(value = sceneId)))
      assert(!request.requestData.fields.contains("sceneName"))
    val id = SceneItemId.from(value = BigDecimal(7)).toOption.get
    assertEquals(scene.show(item = id).sceneItemEnabled, true)
    assertEquals(scene.hide(item = id).sceneItemEnabled, false)
    assertEquals(scene.show(item = id).sceneItemId, BigDecimal(7))
    val input = InputRef.byName(value = "Camera").toOption.get
    Vector(input.settings, input.mute, input.unmute).foreach: request =>
      assertEquals(request.requestData.fields.get("inputName"), Some(JsonValue.Str(value = "Camera")))
      assert(!request.requestData.fields.contains("inputUuid"))
    assertEquals(input.mute.inputMuted, true)
    assertEquals(input.unmute.inputMuted, false)
