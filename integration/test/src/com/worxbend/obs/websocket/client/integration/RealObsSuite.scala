package com.worxbend.obs.websocket.client.integration

import com.worxbend.obs.websocket.client.{ObsConfig, ObsError, PasswordProvider}
import com.worxbend.obs.websocket.client.protocol.requests.{GetSceneList, GetVersion}
import com.worxbend.obs.websocket.client.protocol.requests.{
  CreateScene,
  GetInputList,
  RemoveScene,
  SetCurrentProgramScene
}
import com.worxbend.obs.websocket.client.protocol.Field
import com.worxbend.obs.websocket.client.transport.sttp.SttpObsClient
import munit.FunSuite
import ox.timeoutOption
import scala.concurrent.duration.*

/** Opt-in discovery plus separately enabled disposable scene mutations; absence is an explicit skip. */
class RealObsSuite extends FunSuite:
  private def disposableConfig(): ObsConfig =
    val url = sys.env.get("OBS_WS_URL").filter(_.nonEmpty).getOrElse(fail("Explicit OBS_WS_URL is required"))
    val password =
      sys.env.get("OBS_WS_PASSWORD").filter(_.nonEmpty).getOrElse(fail("Nonempty OBS_WS_PASSWORD is required"))
    ObsConfig(uri = url, passwordProvider = PasswordProvider.fixed(Some(password)))

  test("disposable real OBS rejects an incorrect password"):
    assume(sys.env.get("OBS_INTEGRATION_DISPOSABLE").contains("true"), "Disposable OBS is required")
    val configured = disposableConfig()
    val config = configured.copy(passwordProvider =
      PasswordProvider.fixed(Some("deliberately-wrong-" + java.util.UUID.randomUUID().toString))
    )
    SttpObsClient.connect(config)(_ => ()) match
      case Left(ObsError.Authentication(_, Some(4009))) => ()
      case other => fail(s"Expected OBS authentication rejection with code 4009, received $other")
    println("Verified incorrect-password authentication rejection with close code 4009")

  test("disposable real OBS authenticates and returns version and scenes"):
    assume(
      sys.env.get("OBS_INTEGRATION_DISPOSABLE").contains("true"),
      "Set OBS_INTEGRATION_DISPOSABLE=true for an isolated test OBS"
    )
    val config = disposableConfig()
    val result = SttpObsClient.connect(config): session =>
      for
        version <- session.request(GetVersion())
        scenes <- session.request(GetSceneList())
      yield (version.obsVersion, version.obsWebSocketVersion, scenes.scenes.size)
    val actual = result.flatten.fold(error => fail(s"OBS verification failed: $error"), identity)
    println(s"Verified read-only OBS ${actual._1}, WebSocket ${actual._2}, ${actual._3} scenes")

  test("empty disposable OBS scene switching broadcasts a typed event and cleans up"):
    assume(sys.env.get("OBS_INTEGRATION_DISPOSABLE").contains("true"), "Disposable OBS is required")
    assume(
      sys.env.get("OBS_INTEGRATION_SCENE_MUTATIONS").contains("true"),
      "Only the isolated Docker smoke launcher enables temporary scene mutations"
    )
    val config = disposableConfig()
    val result = SttpObsClient.connect(config): session =>
      val before = session.request(GetSceneList()).fold(error => fail(s"Scene discovery failed: $error"), identity)
      val inputs = session.request(GetInputList()).fold(error => fail(s"Input discovery failed: $error"), identity)
      assertEquals(before.scenes.size, 1, "Mutating test requires exactly one disposable default scene")
      assert(inputs.inputs.isEmpty, "Mutating test requires a source-free disposable collection")
      val original = before.currentProgramSceneName.getOrElse(fail("Expected a current program scene"))
      val temporary = "obs-websocket-client-disposable-test"
      assertNotEquals(original, temporary)
      assert(session.request(CreateScene(sceneName = temporary)).isRight)
      try
        val observed = session.withEvents(Set("CurrentProgramSceneChanged")): events =>
          assert(session.request(SetCurrentProgramScene(sceneName = Field.Value(temporary))).isRight)
          val event = timeoutOption(5.seconds)(events.next())
            .getOrElse(fail("Scene event deadline exceeded"))
            .fold(error => fail(s"Scene subscription failed: $error"), identity)
          event match
            case changed: com.worxbend.obs.websocket.client.protocol.events.CurrentProgramSceneChanged =>
              assertEquals(changed.sceneName, temporary)
            case other => fail(s"Expected typed scene event, received ${other.eventType}")
          assertEquals(session.request(GetSceneList()).toOption.flatMap(_.currentProgramSceneName), Some(temporary))
        assertEquals(observed, Right(()))
      finally
        val restored = session.request(SetCurrentProgramScene(sceneName = Field.Value(original)))
        val removed = session.request(RemoveScene(sceneName = Field.Value(temporary)))
        assert(restored.isRight, s"Disposable scene restoration failed: $restored")
        assert(removed.isRight, s"Disposable scene removal failed: $removed")
      assertEquals(session.request(GetSceneList()).toOption.map(_.scenes.size), Some(1))
      println("Verified disposable scene creation, switching, event delivery, restoration and removal")
    assertEquals(result, Right(()))
