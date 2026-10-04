package com.worxbend.obs.websocket.client.examples

import com.worxbend.obs.websocket.client.{ObsConfig, ObsError, ObsSession, PasswordProvider}
import com.worxbend.obs.websocket.client.protocol.requests.{
  GetSceneList,
  GetSceneListResponse,
  GetVersion,
  GetVersionResponse
}
import com.worxbend.obs.websocket.client.transport.sttp.SttpObsClient

/** Read-only recipes; executing these never starts streaming or changes scenes. */
object Quickstart:
  def discover(session: ObsSession): Either[ObsError, (GetVersionResponse, GetSceneListResponse)] =
    for
      version <- session.request(GetVersion())
      scenes <- session.request(GetSceneList())
    yield (version, scenes)

  def run(config: ObsConfig): Either[ObsError, (GetVersionResponse, GetSceneListResponse)] =
    SttpObsClient.connect(config)(discover).flatten

  def configuration(args: Array[String], environment: Map[String, String]): ObsConfig =
    ObsConfig(
      uri = args.headOption.getOrElse("ws://localhost:4455"),
      passwordProvider = PasswordProvider.fixed(environment.get("OBS_WS_PASSWORD"))
    )

  def main(args: Array[String]): Unit =
    run(configuration(args, sys.env)) match
      case Right((version, scenes)) => println(s"OBS ${version.obsVersion}: ${scenes.scenes.size} scenes")
      case Left(error)              => Console.err.println(s"OBS connection failed: $error")
