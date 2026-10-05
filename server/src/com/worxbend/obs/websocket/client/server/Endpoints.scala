package com.worxbend.obs.websocket.client.server

import _root_.sttp.model.StatusCode
import _root_.sttp.shared.Identity
import _root_.sttp.tapir.*
import _root_.sttp.tapir.json.jsoniter.*
import _root_.sttp.tapir.server.ServerEndpoint
import _root_.sttp.tapir.swagger.bundle.SwaggerInterpreter
import scala.util.control.NonFatal

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
      health.handleSuccess(_ => Health(status = "ok")),
      version.handle(_ => obsVersion(service = service)),
    )
    api ++ SwaggerInterpreter()
      .fromServerEndpoints[Identity](api, "OBS WebSocket client sample", BuildInfo.version)

  /** Unchecked service defects get the same redacted 503 as expected OBS failures. */
  private def obsVersion(service: ObsReadService): Either[ApiFailure, VersionInformation] =
    try service.version().left.map(_ => ApiFailure(message = "OBS is unavailable"))
    catch case NonFatal(_) => Left(ApiFailure(message = "OBS is unavailable"))
