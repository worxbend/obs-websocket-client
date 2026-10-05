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
          .connect(config = config)(use = _.request(request = GetVersion()))
          .flatten
          .map(response => VersionInformation(obs = response.obsVersion, websocket = response.obsWebSocketVersion))
      .getOrElse(Left(ObsError.Timeout(operation = "version")))
