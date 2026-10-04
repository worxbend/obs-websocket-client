package com.worxbend.obs.websocket.client.server

import com.worxbend.obs.websocket.client.{ObsConfig, ObsError}
import com.worxbend.obs.websocket.client.protocol.requests.GetVersion
import com.worxbend.obs.websocket.client.transport.sttp.SttpObsClient
import ox.timeoutOption

private[server] trait ObsReadService:
  def version(): Either[ObsError, VersionInformation]

private[server] object ObsReadService:
  def live(config: ObsConfig): ObsReadService = new ObsReadService:
    def version(): Either[ObsError, VersionInformation] =
      // One overall budget around connect, handshake, and read; without it the layered
      // per-stage timeouts let an unauthenticated caller hold a sync-server thread.
      timeoutOption(config.requestTimeout):
        SttpObsClient
          .connect(config)(_.request(GetVersion()))
          .flatten
          .map(response => VersionInformation(response.obsVersion, response.obsWebSocketVersion))
      .getOrElse(Left(ObsError.Timeout("version")))
