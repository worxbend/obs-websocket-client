package com.worxbend.obs.websocket.client.protocol

import munit.FunSuite
import com.worxbend.obs.websocket.client.protocol.requests.*

class ProtocolSuite extends FunSuite:
  test("wire JSON preserves decimal precision and unknown settings"):
    val text =
      """{"op":6,"d":{"requestId":"α","nested":{"fraction":0.123456789012345678901234567890,"huge":9223372036854775808},"list":[true,false,null,"x"]}}"""
    val decoded = Protocol.decode(text).toOption.get
    assertEquals(Protocol.decode(Protocol.encode(decoded)), Right(decoded))

  test("malformed JSON never leaks its content"):
    List("secret", "{\"password\":\"secret\"", "[1,}").foreach: input =>
      assertEquals(JsonValue.parse(input), Left(ProtocolError("$", "Malformed JSON")))

  test("structural policy violations report their fixed safe reason"):
    assertEquals(JsonValue.parse("{\"x\":1,\"x\":2}"), Left(ProtocolError("$", "duplicate JSON key")))
    assertEquals(JsonValue.parse("[" * 66 + "0" + "]" * 66), Left(ProtocolError("$", "JSON nesting exceeds 64")))
    assertEquals(JsonValue.parse("9" * 309), Left(ProtocolError("$", "value exceeds limit for number of digits")))
    assertEquals(JsonValue.parse("1e6179"), Left(ProtocolError("$", "value exceeds limit for scale")))

  test("wire envelope rejects malformed fields"):
    List("[]", "{}", "{\"op\":1.5,\"d\":{}}", "{\"op\":2147483648,\"d\":{}}", "{\"op\":6,\"d\":false}").foreach:
      input => assert(Protocol.decode(input).isLeft)

  test("JSON byte limit counts UTF-8 bytes"):
    assertEquals(JsonValue.parse("\"α\"", 3), Left(ProtocolError.SizeLimit))
    assertEquals(JsonValue.parse("0", 0), Left(ProtocolError.SizeLimit))
    assertEquals(JsonValue.parse("1234", 3), Left(ProtocolError.SizeLimit))
    assert(JsonValue.parse("\"α\"", 4).isRight)

  test("nested decode failures report full dotted paths"):
    val nested = JsonValue.parse("""{"op":6,"d":{"requestStatus":{"result":true,"code":"high"}}}""").toOption.get
    val envelope = ValueCodec.obj.decode(nested, "$").toOption.get
    val data = envelope.obj("d").toOption.get
    assertEquals(
      data.obj("requestStatus").flatMap(_.int("code")),
      Left(ProtocolError("$.d.requestStatus.code", "Expected number"))
    )
    assertEquals(data.string("missing"), Left(ProtocolError("$.d.missing", "Required field is missing")))
    val direct =
      JsonObject(Map("items" -> JsonValue.Arr(Vector(JsonObject(Map("flag" -> JsonValue.Num(BigDecimal(1))))))))
    assertEquals(
      direct.required("items", ValueCodec.array(ValueCodec.obj)).flatMap(_.head.boolean("flag")),
      Left(ProtocolError("items[0].flag", "Expected boolean"))
    )

  test("JSON depth limit rejects nested payloads"):
    assert(JsonValue.parse("[" * 66 + "0" + "]" * 66).isLeft)

  test("nullable request field emits explicit null while omitted optional field stays absent"):
    val request = SetSceneSceneTransitionOverride(transitionName = Field.Null)
    assertEquals(request.requestData.fields, Map("transitionName" -> JsonValue.Null))
    assertEquals(SetSceneSceneTransitionOverride.decode(request.requestData), Right(request))

  test("required nullable response accepts null but rejects absence"):
    val fields =
      JsonObject(Map("parameterValue" -> JsonValue.Null, "defaultParameterValue" -> JsonValue.Str("default")))
    assertEquals(GetProfileParameterResponse.decode(fields), Right(GetProfileParameterResponse(None, Some("default"))))
    assert(GetProfileParameterResponse.decode(JsonObject.empty).isLeft)

  test("malformed known event is rejected while unknown event is preserved"):
    assert(Event.decode("CurrentProgramSceneChanged", JsonObject.empty).isLeft)
    assertEquals(Event.decode("FutureEvent", JsonObject.empty), Right(UnknownEvent("FutureEvent", JsonObject.empty)))

  test("optional string rejects explicit null"):
    assertEquals(JsonObject.empty.optionalString("x"), Right(None))
    assertEquals(JsonObject(Map("x" -> JsonValue.Str("a"))).optionalString("x"), Right(Some("a")))
    assert(JsonObject(Map("x" -> JsonValue.Null)).optionalString("x").isLeft)

  test("UTF-8 authentication matches independently computed vector"):
    assertEquals(
      Authentication.compute("päss🔒".getBytes(java.nio.charset.StandardCharsets.UTF_8), "salt", "challenge"),
      "hRPwuZ1gatdRB2rztwbomuiw2cq2/yH+EroCZA5oLdo="
    )

  test("JSON rejects mismatched closing delimiters"):
    assert(JsonValue.parse("{\"x\":1]").isLeft)
    assert(JsonValue.parse("[1}").isLeft)

  test("request validation rejects unsupported explicit null before sending"):
    assert(SetCurrentProgramScene(sceneName = Field.Null).validate.isLeft)
    assert(SetCurrentProgramScene(sceneName = Field.Value("main")).validate.isRight)
    assertEquals(RawRequest("NewRequest").requestData, JsonObject.empty)

  test("official authentication-guide inputs match independent SHA-256 calculation"):
    assertEquals(
      Authentication.compute(
        "supersecretpassword".getBytes(java.nio.charset.StandardCharsets.UTF_8),
        "lM1GncleQOaCu9lT1yeUZhFYnqhsLLP1G5lAGo3ixaI=",
        "+IxH4CnCiqpX1rM9scsNynZzbOe4KhDeYcTNS3PDaeY="
      ),
      "1Ct943GAT+6YQUUX47Ia/ncufilbe6+oD6lY+5kaCu4="
    )

  test("numbers beyond DECIMAL128 retain every digit rather than silently rounding"):
    val literals = List(
      "12345678901234567890123456789012345678901234567890",
      "0.12345678901234567890123456789012345678901234567890",
      "12345678901234567890123456789012345678901234567890e-100"
    )
    literals.foreach: literal =>
      val decoded = JsonValue.parse(literal).toOption.get.asInstanceOf[JsonValue.Num]
      assertEquals(decoded.value.bigDecimal.compareTo(new java.math.BigDecimal(literal)), 0)
      assertEquals(JsonValue.parse(JsonValue.render(decoded)), Right(decoded))

  test("excessive numeric mantissas and scales fail without allocating unbounded decimals"):
    assert(JsonValue.parse("9" * 309).isLeft)
    assert(JsonValue.parse("1e6179").isLeft)
