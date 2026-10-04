package com.worxbend.obs.websocket.client.server

import _root_.sttp.model.StatusCode
import _root_.sttp.shared.Identity
import _root_.sttp.tapir.*
import _root_.sttp.tapir.json.jsoniter.*
import _root_.sttp.tapir.server.ServerEndpoint
import _root_.sttp.tapir.swagger.bundle.SwaggerInterpreter

private[server] object Endpoints:
  val health: PublicEndpoint[Unit, Unit, Health, Any] = endpoint.get
    .in("health")
    .description("HTTP server liveness; does not connect to OBS.")
    .out(jsonBody[Health])

  val version: PublicEndpoint[Unit, ApiFailure, VersionInformation, Any] = endpoint.get
    .in("obs" / "version")
    .description("Read-only OBS version discovery using a connection scoped to this request.")
    .errorOut(statusCode(StatusCode.ServiceUnavailable))
    .errorOut(jsonBody[ApiFailure])
    .out(jsonBody[VersionInformation])

  def all(service: ObsReadService): List[ServerEndpoint[Any, Identity]] =
    val api = List(
      health.handleSuccess(_ => Health("ok")),
      version.handle(_ => service.version().left.map(_ => ApiFailure("OBS is unavailable")))
    )
    api ++ SwaggerInterpreter()
      .fromServerEndpoints[Identity](api, "OBS WebSocket client sample", BuildInfo.version)
