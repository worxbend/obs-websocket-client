package com.worxbend.obs.websocket.client.server

import com.worxbend.obs.websocket.client.{ObsConfig, ObsError}
import com.worxbend.obs.websocket.client.protocol.requests.GetVersion
import com.worxbend.obs.websocket.client.transport.sttp.SttpObsClient

private[server] trait ObsReadService:
  def version(): Either[ObsError, VersionInformation]

private[server] object ObsReadService:
  def live(config: ObsConfig): ObsReadService = new ObsReadService:
    def version(): Either[ObsError, VersionInformation] =
      SttpObsClient
        .connect(config)(_.request(GetVersion()))
        .flatten
        .map(response => VersionInformation(response.obsVersion, response.obsWebSocketVersion))
