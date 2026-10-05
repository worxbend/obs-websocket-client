package com.worxbend.obs.websocket.client.protocol

import munit.FunSuite
import com.worxbend.obs.websocket.client.protocol.requests.*
import scala.language.implicitConversions

class ProtocolSuite extends FunSuite:
  private val malformedJsonMessage: String = "Malformed JSON"

  test("wire JSON preserves decimal precision and unknown settings"):
    val text =
      """{"op":6,"d":{"requestId":"α","nested":{"fraction":0.123456789012345678901234567890,"huge":9223372036854775808},"list":[true,false,null,"x"]}}"""
    val decoded = Protocol.decode(text = text).toOption.get
    assertEquals(Protocol.decode(text = Protocol.encode(message = decoded)), Right(decoded))

  test("malformed JSON never leaks its content"):
    List("secret", "{\"password\":\"secret\"", "[1,}").foreach: input =>
      assertEquals(JsonValue.parse(text = input), Left(ProtocolError(path = "$", message = malformedJsonMessage)))

  test("structural policy violations report their fixed safe reason"):
    assertEquals(
      JsonValue.parse(text = "{\"x\":1,\"x\":2}"),
      Left(ProtocolError(path = "$", message = "duplicate JSON key")),
    )
    assertEquals(
      JsonValue.parse(text = "[" * 66 + "0" + "]" * 66),
      Left(ProtocolError(path = "$", message = "JSON nesting exceeds 64")),
    )
    assertEquals(
      JsonValue.parse(text = "9" * 309),
      Left(ProtocolError(path = "$", message = "value exceeds limit for number of digits")),
    )
    assertEquals(
      JsonValue.parse(text = "1e6179"),
      Left(ProtocolError(path = "$", message = "value exceeds limit for scale")),
    )

  test("decode failures from throwables with null or unrelated messages stay Malformed JSON"):
    assertEquals(
      JsonValue.decodeFailure(error = new RuntimeException()),
      ProtocolError(path            = "$", message = malformedJsonMessage),
    )
    assertEquals(
      JsonValue.decodeFailure(error = new RuntimeException("unrelated")),
      ProtocolError(path            = "$", message = malformedJsonMessage),
    )

  test("wire envelope rejects malformed fields"):
    List("[]", "{}", "{\"op\":1.5,\"d\":{}}", "{\"op\":2147483648,\"d\":{}}", "{\"op\":6,\"d\":false}").foreach:
      input => assert(Protocol.decode(text = input).isLeft)

  test("JSON byte limit counts UTF-8 bytes"):
    assertEquals(JsonValue.parse(text = "\"α\"", maxBytes = 3), Left(ProtocolError.SizeLimit))
    assertEquals(JsonValue.parse(text = "0", maxBytes = 0), Left(ProtocolError.SizeLimit))
    assertEquals(JsonValue.parse(text = "1234", maxBytes = 3), Left(ProtocolError.SizeLimit))
    assert(JsonValue.parse(text = "\"α\"", maxBytes = 4).isRight)

  test("nested decode failures report full dotted paths"):
    val nested = JsonValue.parse(text = """{"op":6,"d":{"requestStatus":{"result":true,"code":"high"}}}""").toOption.get
    val envelope = ValueCodec.obj.decode(value = nested, path = "$").toOption.get
    val data     = envelope.obj(name = "d").toOption.get
    assertEquals(
      data.obj(name = "requestStatus").flatMap(_.int(name = "code")),
      Left(ProtocolError(path = "$.d.requestStatus.code", message = "Expected number")),
    )
    assertEquals(
      data.string(name = "missing"),
      Left(ProtocolError(path = "$.d.missing", message = "Required field is missing")),
    )
    val direct =
      JsonObject(fields =
        Map(
          "items" -> JsonValue.Arr(value =
            Vector(JsonObject(fields = Map("flag" -> JsonValue.Num(value = BigDecimal(1)))))
          )
        )
      )
    assertEquals(
      direct
        .required(name = "items", codec = ValueCodec.array(element = ValueCodec.obj))
        .flatMap(_.head.boolean(name = "flag")),
      Left(ProtocolError(path = "items[0].flag", message = "Expected boolean")),
    )

  test("JSON depth limit rejects nested payloads"):
    assert(JsonValue.parse(text = "[" * 66 + "0" + "]" * 66).isLeft)

  test("nullable request field emits explicit null while omitted optional field stays absent"):
    val request = SetSceneSceneTransitionOverride(transitionName = Field.Null)
    assertEquals(request.requestData.fields, Map("transitionName" -> JsonValue.Null))
    assertEquals(SetSceneSceneTransitionOverride.decode(data = request.requestData), Right(request))

  test("required nullable response accepts null but rejects absence"):
    val fields =
      JsonObject(fields =
        Map("parameterValue" -> JsonValue.Null, "defaultParameterValue" -> JsonValue.Str(value = "default"))
      )
    assertEquals(
      GetProfileParameterResponse.decode(data = fields),
      Right(GetProfileParameterResponse(parameterValue = None, defaultParameterValue = Some("default"))),
    )
    assert(GetProfileParameterResponse.decode(data = JsonObject.empty).isLeft)

  test("malformed known event is rejected while unknown event is preserved"):
    assert(Event.decode(eventType = "CurrentProgramSceneChanged", data = JsonObject.empty).isLeft)
    assertEquals(
      Event.decode(eventType = "FutureEvent", data = JsonObject.empty),
      Right(UnknownEvent(eventType = "FutureEvent", eventData = JsonObject.empty)),
    )

  test("optional string rejects explicit null"):
    assertEquals(JsonObject.empty.optionalString(name = "x"), Right(None))
    assertEquals(
      JsonObject(fields = Map("x" -> JsonValue.Str(value = "a"))).optionalString(name = "x"),
      Right(Some("a")),
    )
    assert(JsonObject(fields = Map("x" -> JsonValue.Null)).optionalString(name = "x").isLeft)

  test("UTF-8 authentication matches independently computed vector"):
    assertEquals(
      Authentication.compute(
        password  = "päss🔒".getBytes(java.nio.charset.StandardCharsets.UTF_8),
        salt      = "salt",
        challenge = "challenge",
      ),
      "hRPwuZ1gatdRB2rztwbomuiw2cq2/yH+EroCZA5oLdo=",
    )

  test("JSON rejects mismatched closing delimiters"):
    assert(JsonValue.parse(text = "{\"x\":1]").isLeft)
    assert(JsonValue.parse(text = "[1}").isLeft)

  test("request validation rejects unsupported explicit null before sending"):
    assert(SetCurrentProgramScene(sceneName = Field.Null).validate.isLeft)
    assert(SetCurrentProgramScene(sceneName = Field.Value(value = "main")).validate.isRight)
    assertEquals(RawRequest(requestType = "NewRequest").requestData, JsonObject.empty)

  test("a plain value converts to a supplied optional field"):
    val field: Field[String] = "Studio"
    assertEquals(field, Field.Value(value = "Studio"))
    assertEquals(SetCurrentProgramScene(sceneName = "Studio").sceneName, Field.Value(value = "Studio"))

  test("official authentication-guide inputs match independent SHA-256 calculation"):
    assertEquals(
      Authentication.compute(
        password  = "supersecretpassword".getBytes(java.nio.charset.StandardCharsets.UTF_8),
        salt      = "lM1GncleQOaCu9lT1yeUZhFYnqhsLLP1G5lAGo3ixaI=",
        challenge = "+IxH4CnCiqpX1rM9scsNynZzbOe4KhDeYcTNS3PDaeY=",
      ),
      "1Ct943GAT+6YQUUX47Ia/ncufilbe6+oD6lY+5kaCu4=",
    )

  test("numbers beyond DECIMAL128 retain every digit rather than silently rounding"):
    val literals = List(
      "12345678901234567890123456789012345678901234567890",
      "0.12345678901234567890123456789012345678901234567890",
      "12345678901234567890123456789012345678901234567890e-100",
    )
    literals.foreach: literal =>
      val decoded = JsonValue.parse(text = literal).toOption.get.asInstanceOf[JsonValue.Num]
      assertEquals(decoded.value.bigDecimal.compareTo(new java.math.BigDecimal(literal)), 0)
      assertEquals(JsonValue.parse(text = JsonValue.render(value = decoded)), Right(decoded))

  test("excessive numeric mantissas and scales fail without allocating unbounded decimals"):
    assert(JsonValue.parse(text = "9" * 309).isLeft)
    assert(JsonValue.parse(text = "1e6179").isLeft)
