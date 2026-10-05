package com.worxbend.obs.websocket.client.examples

import com.worxbend.obs.websocket.client.{ObsConfig, ObsError, ObsSession, PasswordProvider}
import com.worxbend.obs.websocket.client.protocol.requests.{
  GetSceneList,
  GetSceneListResponse,
  GetVersion,
  GetVersionResponse,
}
import com.worxbend.obs.websocket.client.transport.sttp.SttpObsClient

/** Read-only recipes; executing these never starts streaming or changes scenes. */
object Quickstart:
  def discover(session: ObsSession): Either[ObsError, (GetVersionResponse, GetSceneListResponse)] =
    for
      version <- session.request(request = GetVersion())
      scenes  <- session.request(request = GetSceneList())
    yield (version, scenes)

  def run(config: ObsConfig): Either[ObsError, (GetVersionResponse, GetSceneListResponse)] =
    SttpObsClient.connect(config = config)(use = discover).flatten

  def configuration(args: Array[String], environment: Map[String, String]): ObsConfig =
    ObsConfig(
      uri = args.headOption.getOrElse("ws://localhost:4455"),
      // A blank password (e.g. an empty OBS_WS_PASSWORD) means no authentication, matching ObsSettings.
      passwordProvider = PasswordProvider.fixed(value = environment.get("OBS_WS_PASSWORD").filter(_.trim.nonEmpty)),
    )

  /** Execute the CLI without terminating its process, so embedders and tests can inspect the status. */
  private[examples] def execute(args: Array[String], environment: Map[String, String]): Int =
    run(config = configuration(args = args, environment = environment)) match
      case Right((version, scenes)) =>
        println(s"OBS ${version.obsVersion}: ${scenes.scenes.size} scenes")
        0
      case Left(error) =>
        Console.err.println(s"OBS connection failed: $error")
        1

  def main(args: Array[String]): Unit =
    val status = execute(args = args, environment = sys.env)
    if status != 0 then sys.exit(status)
