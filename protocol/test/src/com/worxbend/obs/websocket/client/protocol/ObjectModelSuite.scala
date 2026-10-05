package com.worxbend.obs.websocket.client.protocol

import com.worxbend.obs.websocket.client.protocol.models.*
import munit.FunSuite
import java.nio.charset.StandardCharsets.UTF_8

class ObjectModelSuite extends FunSuite:
  private val sceneId: String = "scene-id"

  private def resource(name: String): JsonValue =
    val stream = getClass.getResourceAsStream(name)
    try JsonValue.parse(String(stream.readAllBytes(), UTF_8)).toOption.get
    finally stream.close()

  private val fixtures = resource("/model-fixtures.json").asInstanceOf[JsonObject]
  private val catalog =
    resource("/catalog-fixtures.json").asInstanceOf[JsonValue.Arr].value.map(_.asInstanceOf[JsonObject])
  private val codecs: Vector[(String, ValueCodec[? <: ObjectModel])] = Vector(
    "Scene" -> Scene.codec,
    "Input" -> Input.codec,
    "SceneItem" -> SceneItem.codec,
    "SceneItemTransform" -> SceneItemTransform.codec,
    "Monitor" -> Monitor.codec,
    "Filter" -> Filter.codec,
    "OutputFlags" -> OutputFlags.codec,
    "Output" -> Output.codec,
    "Transition" -> Transition.codec,
    "PropertyItem" -> PropertyItem.codec,
    "InputVolumeMeter" -> InputVolumeMeter.codec,
    "Canvas" -> Canvas.codec
  )

  codecs.foreach: (name, existentialCodec) =>
    val codec = existentialCodec.asInstanceOf[ValueCodec[ObjectModel]]
    val fixture = fixtures.obj(name).toOption.get
    val valid = fixture.obj("valid").toOption.get
    val fields = fixture.array("fields").toOption.get.map(_.asInstanceOf[JsonObject])
    test(s"$name view validates fields and preserves the complete future payload"):
      val extended = valid.copy(fields = valid.fields.updated("futureSetting", JsonValue.Str("preserved")))
      val model = codec.decode(extended, "payload").toOption.get
      assertEquals(model.raw, extended)
      assertEquals(model.toJson, extended)
      assertEquals(codec.encode(model), extended)
      fields.foreach: field =>
        val getter = model.getClass.getMethod(field.string("name").toOption.get)
        assert(getter.invoke(model) != null)
      assertEquals(codec.decode(JsonValue.Bool(false), "payload"), Left(ProtocolError("payload", "Expected object")))
    fields.foreach: field =>
      val key = field.string("name").toOption.get
      val optional = field.boolean("optional").toOption.get
      test(s"$name.$key missing field compatibility"):
        val decoded = codec.decode(valid.copy(fields = valid.fields - key), "payload")
        assertEquals(decoded.isRight, optional)
        if optional then
          assertEquals(decoded.toOption.get.getClass.getMethod(key).invoke(decoded.toOption.get), Field.Missing)
        else assertEquals(decoded.swap.toOption.get.path, s"payload.$key")
      test(s"$name.$key explicit null"):
        val decoded = codec.decode(valid.copy(fields = valid.fields.updated(key, JsonValue.Null)), "payload")
        assertEquals(decoded.isRight, optional || field.boolean("any").toOption.get)
        if optional then
          assertEquals(decoded.toOption.get.getClass.getMethod(key).invoke(decoded.toOption.get), Field.Null)
      if !field.boolean("any").toOption.get then
        test(s"$name.$key malformed value is never treated as absent"):
          val wrong = JsonValue.Arr(Vector(JsonValue.Str("wrong")))
          assert(codec.decode(valid.copy(fields = valid.fields.updated(key, wrong)), "payload").isLeft)

  test("nested scene-item transform errors include the owning array index and field path"):
    val item = fixtures.obj("SceneItem").toOption.get.obj("valid").toOption.get
    val malformed = item.copy(fields = item.fields.updated("sceneItemTransform", JsonObject.empty))
    val decoded = ValueCodec.array(SceneItem.codec).decode(JsonValue.Arr(Vector(item, malformed)), "sceneItems")
    assertEquals(decoded.swap.toOption.get.path, "sceneItems[1].sceneItemTransform.alignment")

  test("synthetic pre-UUID and current scene payloads decode without claiming live compatibility"):
    val old = JsonObject(Map("sceneName" -> JsonValue.Str("Main"), "sceneIndex" -> JsonValue.Num(BigDecimal(0))))
    val modern = old.copy(fields = old.fields.updated("sceneUuid", JsonValue.Str("server-assigned-identifier")))
    assertEquals(Scene.decode(old).toOption.get.sceneUuid, Field.Missing)
    assertEquals(Scene.decode(modern).toOption.get.sceneUuid, Field.Value("server-assigned-identifier"))

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
      ("SceneListChanged", "scenes", "Scene", true)
    )
    views.foreach: (owner, field, model, array) =>
      val response = owner.endsWith("Response")
      val name = owner.stripSuffix("Response")
      val fixture = catalog
        .find(f =>
          f.string("kind").contains(if response then "response" else "event") && f.string("name").contains(name)
        )
        .get
      val raw = fixtures.obj(model).toOption.get.obj("valid").toOption.get
      val value = if array then JsonValue.Arr(Vector(raw)) else raw
      val data = fixture.obj("valid").toOption.get
      val payload = data.copy(fields = data.fields.updated(field, value))
      val decoded = if response then
        val requestFixture =
          catalog.find(f => f.string("kind").contains("request") && f.string("name").contains(name)).get
        Catalog
          .decodeRequest(name, requestFixture.obj("valid").toOption.get)
          .toOption
          .get
          .decodeResponse(payload)
          .toOption
          .get
      else Event.decode(name, payload).toOption.get
      val method = TypedPayloads.getClass.getDeclaredMethods
        .find(m => m.getParameterCount == 1 && m.getParameterTypes.head.getSimpleName == owner)
        .get
      val result = method.invoke(TypedPayloads, decoded.asInstanceOf[Object]).asInstanceOf[Either[ProtocolError, Any]]
      val projected = if array then result.toOption.get.asInstanceOf[Vector[ObjectModel]].head
      else result.toOption.get.asInstanceOf[ObjectModel]
      assertEquals(projected.raw, raw)

  test("name and UUID references select exactly one field and retain names verbatim"):
    assert(SceneRef.byName("  ").isLeft)
    assert(SceneRef.byUuid("").isLeft)
    assert(InputRef.byName("").isLeft)
    assert(InputRef.byUuid(" \t ").isLeft)
    val scene = SceneRef.byName(" Main ").toOption.get
    assertEquals(scene.name, Field.Value(" Main "))
    assertEquals(scene.uuid, Field.Missing)
    val sceneUuid = SceneRef.byUuid(sceneId).toOption.get
    assertEquals(sceneUuid.name, Field.Missing)
    assertEquals(sceneUuid.uuid, Field.Value(sceneId))
    val input = InputRef.byName("Microphone").toOption.get
    assertEquals(input.name, Field.Value("Microphone"))
    assertEquals(input.uuid, Field.Missing)
    val inputUuid = InputRef.byUuid("input-id").toOption.get
    assertEquals(inputUuid.name, Field.Missing)
    assertEquals(inputUuid.uuid, Field.Value("input-id"))

  test("scene-item identifiers reject fractional and negative numbers without narrowing"):
    assert(SceneItemId.from(BigDecimal(-1)).isLeft)
    assert(SceneItemId.from(BigDecimal("1.5")).isLeft)
    assertEquals(SceneItemId.from(BigDecimal(0)).toOption.get.value, BigDecimal(0))
    val large = BigDecimal("100000000000000000000")
    assertEquals(SceneItemId.from(large).toOption.get.value, large)

  test("validated references build requests without conflicting name and UUID selectors"):
    val scene = SceneRef.byUuid(sceneId).toOption.get
    val sceneRequests = Vector(
      scene.setProgram,
      scene.setPreview,
      scene.items,
      scene.findItem("Camera"),
      scene.findItem("Camera", Field.Value(BigDecimal(1)))
    )
    sceneRequests.foreach: request =>
      assertEquals(request.requestData.fields.get("sceneUuid"), Some(JsonValue.Str(sceneId)))
      assert(!request.requestData.fields.contains("sceneName"))
    val id = SceneItemId.from(BigDecimal(7)).toOption.get
    assertEquals(scene.show(id).sceneItemEnabled, true)
    assertEquals(scene.hide(id).sceneItemEnabled, false)
    assertEquals(scene.show(id).sceneItemId, BigDecimal(7))
    val input = InputRef.byName("Camera").toOption.get
    Vector(input.settings, input.mute, input.unmute).foreach: request =>
      assertEquals(request.requestData.fields.get("inputName"), Some(JsonValue.Str("Camera")))
      assert(!request.requestData.fields.contains("inputUuid"))
    assertEquals(input.mute.inputMuted, true)
    assertEquals(input.unmute.inputMuted, false)
