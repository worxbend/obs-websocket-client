package com.worxbend.obs.websocket.client.protocol

import munit.FunSuite
import java.nio.charset.StandardCharsets.UTF_8

class CatalogSuite extends FunSuite:
  private val fixtureStream =
    val stream = getClass.getResourceAsStream("/catalog-fixtures.json")
    require(stream != null, "Missing test resource /catalog-fixtures.json")
    stream
  private val fixtures =
    try JsonValue.parse(String(fixtureStream.readAllBytes(), UTF_8)).toOption.get.asInstanceOf[JsonValue.Arr].value
    finally fixtureStream.close()

  private def decode(kind: String, name: String, data: JsonObject): Either[ProtocolError, JsonObject] = kind match
    case "request" =>
      Catalog
        .decodeRequest(name, data)
        .map: request =>
          assertEquals(request.requestType, name)
          request.requestData
    case "response" => Catalog.roundTripResponse(name, data)
    case _          =>
      Event
        .decode(name, data)
        .map: event =>
          assertEquals(event.eventType, name)
          event.eventData

  private def replaceField(data: JsonObject, path: String, value: Option[JsonValue]): JsonObject =
    val parts = path.split("\\.", 2)
    if parts.length == 1 then
      data.copy(fields = value match
        case Some(replacement) => data.fields.updated(path, replacement)
        case None              => data.fields - path)
    else
      data.copy(fields = data.fields.updated(parts(0), replaceField(data.obj(parts(0)).toOption.get, parts(1), value)))

  fixtures.foreach: json =>
    val fixture = json.asInstanceOf[JsonObject]
    val kind = fixture.string("kind").toOption.get
    val name = fixture.string("name").toOption.get
    val valid = fixture.obj("valid").toOption.get
    val fields = fixture.array("fields").toOption.get.map(_.asInstanceOf[JsonObject])
    test(s"$kind $name valid fixture and unknown-field compatibility"):
      assertEquals(decode(kind, name, valid), Right(valid))
      val extended = valid.copy(fields = valid.fields.updated("futureField", JsonValue.Bool(true)))
      assertEquals(decode(kind, name, extended), Right(valid))
    fields.foreach: field =>
      val fieldName = field.string("name").toOption.get
      val optional = field.boolean("optional").toOption.get
      val nullable = field.boolean("nullable").toOption.get
      val any = field.string("type").toOption.get == "Any"
      test(s"$kind $name.$fieldName omission"):
        val missing = replaceField(valid, fieldName, None)
        assertEquals(decode(kind, name, missing).isRight, optional)
      test(s"$kind $name.$fieldName nullability"):
        val nulled = replaceField(valid, fieldName, Some(JsonValue.Null))
        assertEquals(decode(kind, name, nulled).isRight, nullable || any)
      if !any then
        test(s"$kind $name.$fieldName malformed type"):
          val invalid =
            replaceField(valid, fieldName, Some(JsonValue.Arr(Vector(JsonValue.Bool(false)))))
          assert(decode(kind, name, invalid).isLeft)

  test("all request response instance decoders match registry"):
    fixtures
      .filter(_.asInstanceOf[JsonObject].string("kind").toOption.contains("request"))
      .foreach: value =>
        val fixture = value.asInstanceOf[JsonObject]
        val name = fixture.string("name").toOption.get
        val request = Catalog.decodeRequest(name, fixture.obj("valid").toOption.get).toOption.get
        val response = fixtures
          .map(_.asInstanceOf[JsonObject])
          .find(f => f.string("kind").toOption.contains("response") && f.string("name").toOption.contains(name))
          .get
        assert(request.decodeResponse(response.obj("valid").toOption.get).isRight)

  test("catalog counts match the pinned schema provenance"):
    // Counts recorded in protocol-spec/provenance.json for the pinned schema revision.
    val requests = fixtures.map(_.asInstanceOf[JsonObject]).filter(_.string("kind").toOption.contains("request"))
    val events = fixtures.map(_.asInstanceOf[JsonObject]).filter(_.string("kind").toOption.contains("event"))
    assertEquals(requests.size, 147)
    assertEquals(events.size, 60)
    assertEquals(Catalog.requestNames.size, requests.size)
    events.foreach: fixture =>
      val name = fixture.string("name").toOption.get
      val decoded = Event.decode(name, fixture.obj("valid").toOption.get).toOption.get
      assert(!decoded.isInstanceOf[UnknownEvent], s"$name must decode to a generated event type")
    val pinnedEnums =
      List(
        "EventSubscription",
        "RequestBatchExecutionType",
        "RequestStatus",
        "ObsOutputState",
        "ObsMediaInputAction",
        "WebSocketCloseCode",
        "WebSocketOpCode"
      )
    assertEquals(
      pinnedEnums.map(name => Class.forName(s"com.worxbend.obs.websocket.client.protocol.enums.$name").getSimpleName),
      pinnedEnums
    )

  test("raw request and unknown response preserve data"):
    val data = JsonObject(Map("future" -> JsonValue.Num(BigDecimal(1))))
    val request = Catalog.decodeRequest("Future", data).toOption.get
    assertEquals(request.requestType, "Future")
    assertEquals(request.requestData, data)
    assert(request.decodeResponse(data) == Right(data))
    assertEquals(Catalog.roundTripResponse("Future", data), Right(data))

  test("requests built with all-default fields encode none of the optional keys"):
    // The contract defaults = omission: every generated baseline omits its optional keys on the wire.
    val optionalByName = fixtures
      .map(_.asInstanceOf[JsonObject])
      .filter(_.string("kind").toOption.contains("request"))
      .map: fixture =>
        fixture.string("name").toOption.get ->
          fixture
            .array("fields")
            .toOption
            .get
            .map(_.asInstanceOf[JsonObject])
            .filter(_.boolean("optional").toOption.get)
            .map(_.string("name").toOption.get)
            .toSet
      .toMap
    assertEquals(Catalog.minimalRequests.size, optionalByName.size)
    Catalog.minimalRequests.foreach: request =>
      val optionalKeys = optionalByName(request.requestType)
      assert(
        request.requestData.fields.keySet.intersect(optionalKeys).isEmpty,
        s"${request.requestType} encodes omitted default fields"
      )

  test("omitted optional fields stay absent when decoded requests are re-encoded"):
    // Defaults mean omission: a request decoded without its optional keys encodes none of them.
    fixtures
      .map(_.asInstanceOf[JsonObject])
      .filter(_.string("kind").toOption.contains("request"))
      .foreach: fixture =>
        val name = fixture.string("name").toOption.get
        val valid = fixture.obj("valid").toOption.get
        val optionalKeys = fixture
          .array("fields")
          .toOption
          .get
          .map(_.asInstanceOf[JsonObject])
          .filter(_.boolean("optional").toOption.get)
          .map(_.string("name").toOption.get)
          .toSet
        val stripped = valid.copy(fields = valid.fields -- optionalKeys)
        val request = Catalog.decodeRequest(name, stripped).toOption.get
        assert(
          request.requestData.fields.keySet.intersect(optionalKeys).isEmpty,
          s"$name re-encodes omitted optional fields"
        )

  test("generated enum constants retain published values and admit unknowns"):
    import enums.*
    assertEquals(EventSubscription.All.value, 4095L)
    assertEquals(RequestBatchExecutionType.SerialRealtime.value, 0L)
    assertEquals(RequestStatus.Success.value, 100L)
    assertEquals(WebSocketOpCode.Hello.value, 0L)
    assertEquals(WebSocketCloseCode.AuthenticationFailed.value, 4009L)
    assertEquals(ObsOutputState.OBS_WEBSOCKET_OUTPUT_STARTED.value, "OBS_WEBSOCKET_OUTPUT_STARTED")
    assertEquals(
      ObsMediaInputAction.OBS_WEBSOCKET_MEDIA_INPUT_ACTION_PLAY.value,
      "OBS_WEBSOCKET_MEDIA_INPUT_ACTION_PLAY"
    )
    assertEquals(RequestStatus(9999).value, 9999L)
