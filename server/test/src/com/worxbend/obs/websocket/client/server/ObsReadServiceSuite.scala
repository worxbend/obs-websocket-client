package com.worxbend.obs.websocket.client.server

import com.worxbend.obs.websocket.client.ObsError
import com.worxbend.obs.websocket.client.protocol.{JsonObject, JsonValue}
import com.worxbend.obs.websocket.client.protocol.requests.GetVersionResponse

class ObsReadServiceSuite extends munit.FunSuite:
  private val discovery = GetVersionResponse(
    obsVersion            = "32.0.0",
    obsWebSocketVersion   = "5.6.3",
    rpcVersion            = BigDecimal(1),
    availableRequests     = Vector("GetVersion"),
    supportedImageFormats = Vector("png"),
    platform              = "linux",
    platformDescription   = "test",
  ).toJson

  test("missing immutable startup discovery is an internal error"):
    assertEquals(
      ObsReadService.decodeDiscovery(discovery = None),
      Left(ObsError.InternalError(message = "Missing startup discovery")),
    )

  test("malformed discovery retains the structural field path without leaking payload contents"):
    val malformed = discovery.copy(fields = discovery.fields.updated("obsVersion", JsonValue.Num(BigDecimal(32))))
    assertEquals(
      ObsReadService.decodeDiscovery(discovery = Some(malformed)),
      Left(ObsError.MalformedPayload(path = "obsVersion", message = "Expected string")),
    )

  test("present discovery with missing required fields is a malformed payload rather than absence"):
    assertEquals(
      ObsReadService.decodeDiscovery(discovery = Some(JsonObject.empty)),
      Left(ObsError.MalformedPayload(path = "obsVersion", message = "Required field is missing")),
    )

  test("valid startup discovery maps the OBS and WebSocket versions without another request"):
    assertEquals(
      ObsReadService.decodeDiscovery(discovery = Some(discovery)),
      Right(VersionInformation(obs = "32.0.0", websocket = "5.6.3")),
    )
