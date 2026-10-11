package com.worxbend.obs.websocket.client.server

import com.worxbend.obs.websocket.client.{ObsConfig, ObsError}
import com.worxbend.obs.websocket.client.protocol.JsonObject
import com.worxbend.obs.websocket.client.protocol.requests.GetVersion
import com.worxbend.obs.websocket.client.transport.sttp.SttpObsClient

private[server] trait ObsReadService:
  def version(): Either[ObsError, VersionInformation]

private[server] object ObsReadService:
  def decodeDiscovery(discovery: Option[JsonObject]): Either[ObsError, VersionInformation] =
    discovery
      .toRight(ObsError.InternalError(message = "Missing startup discovery"))
      .flatMap: raw =>
        GetVersion().decodeResponse(data = raw).left.map: error =>
          ObsError.MalformedPayload(path = error.path, message = error.message)
      .map(response => VersionInformation(obs = response.obsVersion, websocket = response.obsWebSocketVersion))

  def live(config: ObsConfig): ObsReadService = new ObsReadService:
    def version(): Either[ObsError, VersionInformation] =
      SttpObsClient
        .connectWithin(config = config, budget = config.requestTimeout): session =>
          // Generation-scoped discovery is immutable; no second request or startup race.
          decodeDiscovery(discovery = session.discovery)
        .flatten
        .left
        .map:
          case _: ObsError.Timeout => ObsError.Timeout(operation = "version")
          case error               => error
