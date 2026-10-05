package com.worxbend.obs.websocket.client.integration

import com.worxbend.obs.websocket.client.{EventSubscriptions, Next, ObsConfig, ObsError, PasswordProvider}
import com.worxbend.obs.websocket.client.protocol.events.CurrentProgramSceneChanged
import com.worxbend.obs.websocket.client.protocol.requests.{GetSceneList, GetVersion}
import com.worxbend.obs.websocket.client.protocol.requests.{
  CreateScene,
  GetInputList,
  RemoveScene,
  SetCurrentProgramScene,
}
import com.worxbend.obs.websocket.client.protocol.Field
import com.worxbend.obs.websocket.client.transport.sttp.SttpObsClient
import munit.FunSuite
import ox.timeoutOption

/** Opt-in discovery plus separately enabled disposable scene mutations; absence is an explicit skip. */
class RealObsSuite extends FunSuite:
  private val disposableObsRequired: String = "Disposable OBS is required"

  private def disposableConfig(): ObsConfig =
    val url      = sys.env.get("OBS_WS_URL").filter(_.nonEmpty).getOrElse(fail("Explicit OBS_WS_URL is required"))
    val password =
      sys.env.get("OBS_WS_PASSWORD").filter(_.nonEmpty).getOrElse(fail("Nonempty OBS_WS_PASSWORD is required"))
    ObsConfig(uri = url, passwordProvider = PasswordProvider.fixed(value = Some(password)))

  test("disposable real OBS rejects an incorrect password"):
    assume(sys.env.get("OBS_INTEGRATION_DISPOSABLE").contains("true"), disposableObsRequired)
    val configured = disposableConfig()
    val config     = configured.copy(passwordProvider =
      PasswordProvider.fixed(value = Some("deliberately-wrong-" + java.util.UUID.randomUUID().toString))
    )
    SttpObsClient.connect(config = config)(_ => ()) match
      case Left(ObsError.Authentication(_, Some(4009))) => ()
      case other => fail(s"Expected OBS authentication rejection with code 4009, received $other")
    println("Verified incorrect-password authentication rejection with close code 4009")

  test("disposable real OBS authenticates and returns version and scenes"):
    assume(
      sys.env.get("OBS_INTEGRATION_DISPOSABLE").contains("true"),
      "Set OBS_INTEGRATION_DISPOSABLE=true for an isolated test OBS",
    )
    val config = disposableConfig()
    val result = SttpObsClient.connect(config = config): session =>
      for
        version <- session.request(request = GetVersion())
        scenes  <- session.request(request = GetSceneList())
      yield (version.obsVersion, version.obsWebSocketVersion, scenes.scenes.size)
    val actual = result.flatten.fold(error => fail(s"OBS verification failed: $error"), identity)
    println(s"Verified read-only OBS ${actual._1}, WebSocket ${actual._2}, ${actual._3} scenes")

  test("disposable real OBS acknowledges subscription updates without disconnecting"):
    assume(sys.env.get("OBS_INTEGRATION_DISPOSABLE").contains("true"), disposableObsRequired)
    val result = SttpObsClient.connect(config = disposableConfig()): session =>
      // Each request after a reidentify forces its uncorrelated Identified ack to arrive first (the server
      // processes frames in order), so a zero observed backlog proves the ack was actually received.
      for
        _             <- session.reidentify(subscriptions = EventSubscriptions.none)
        _             <- session.request(request = GetVersion())
        firstBacklog  <- session.pendingReidentifyAcks
        _             <- session.reidentify(subscriptions = EventSubscriptions.normal)
        version       <- session.request(request = GetVersion())
        secondBacklog <- session.pendingReidentifyAcks
      yield (firstBacklog, secondBacklog, version.obsWebSocketVersion)
    val (firstBacklog, secondBacklog, _) =
      result.flatten.fold(error => fail(s"Reidentify verification failed: $error"), identity)
    assertEquals(firstBacklog, 0, "Server never acknowledged the first Reidentify")
    assertEquals(secondBacklog, 0, "Server never acknowledged the second Reidentify")
    println("Verified Reidentify acknowledgements preserve the live OBS session")

  test("empty disposable OBS scene switching broadcasts a typed event and cleans up"):
    assume(sys.env.get("OBS_INTEGRATION_DISPOSABLE").contains("true"), disposableObsRequired)
    assume(
      sys.env.get("OBS_INTEGRATION_SCENE_MUTATIONS").contains("true"),
      "Only the isolated Docker smoke launcher enables temporary scene mutations",
    )
    val config = disposableConfig()
    val result = SttpObsClient.connect(config = config): session =>
      val before =
        session.request(request = GetSceneList()).fold(error => fail(s"Scene discovery failed: $error"), identity)
      val inputs =
        session.request(request = GetInputList()).fold(error => fail(s"Input discovery failed: $error"), identity)
      assertEquals(before.scenes.size, 1, "Mutating test requires exactly one disposable default scene")
      assert(inputs.inputs.isEmpty, "Mutating test requires a source-free disposable collection")
      val original  = before.currentProgramSceneName.getOrElse(fail("Expected a current program scene"))
      val temporary = "obs-websocket-client-disposable-test"
      assertNotEquals(original, temporary)
      assert(session.request(request = CreateScene(sceneName = temporary)).isRight)
      try
        val observed = session.withEvents(eventTypes = Set("CurrentProgramSceneChanged")): events =>
          assert(session.request(request = SetCurrentProgramScene(sceneName = Field.Value(value = temporary))).isRight)
          val event = timeoutOption(config.requestTimeout)(events.next())
            .getOrElse(fail("Scene event deadline exceeded")) match
            case Next.Item(event)   => event
            case Next.Failed(error) => fail(s"Scene subscription failed: $error")
            case Next.Ended         => fail("Scene subscription ended")
          event match
            case changed: CurrentProgramSceneChanged =>
              assertEquals(changed.sceneName, temporary)
            case other => fail(s"Expected typed scene event, received ${other.eventType}")
          val switched =
            session
              .request(request = GetSceneList())
              .fold(error => fail(s"Scene verification failed: $error"), identity)
          assertEquals(switched.currentProgramSceneName, Some(temporary))
        assertEquals(observed, Right(()))
      finally
        val restored = session.request(request = SetCurrentProgramScene(sceneName = Field.Value(value = original)))
        val removed  = session.request(request = RemoveScene(sceneName = Field.Value(value = temporary)))
        assert(restored.isRight, s"Disposable scene restoration failed: $restored")
        assert(removed.isRight, s"Disposable scene removal failed: $removed")
      val remaining =
        session
          .request(request = GetSceneList())
          .fold(error => fail(s"Scene cleanup verification failed: $error"), identity)
      assertEquals(remaining.scenes.size, 1)
      println("Verified disposable scene creation, switching, event delivery, restoration and removal")
    assertEquals(result, Right(()))
