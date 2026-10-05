package com.worxbend.obs.websocket.client.transport.pekko

import com.worxbend.obs.websocket.client.ObsError
import munit.FunSuite
import scala.concurrent.duration.*

class PekkoOptionsSuite extends FunSuite:
  test("write deadline defaults and renders no header values"):
    val options = PekkoOptions()
    assertEquals(options.validate, Right(options))
    assertEquals(options.toString, "PekkoOptions(writeTimeout=10 seconds, headers=<redacted>, readIdleTimeout=None)")
    val invalid = PekkoOptions(writeTimeout = Duration.Zero)
    assertEquals(invalid.validate, Left(ObsError.InvalidConfiguration(message = "Write deadline must be positive")))

  test("read idle deadline is opt-in and validates positivity"):
    assertEquals(PekkoOptions().readIdleTimeout, None)
    val liveness = PekkoOptions(readIdleTimeout = Some(30.seconds))
    assertEquals(liveness.validate, Right(liveness))
    assert(liveness.toString.contains("readIdleTimeout=Some(30 seconds)"))
    val invalid = Left(ObsError.InvalidConfiguration(message = "Read idle deadline must be positive"))
    assertEquals(PekkoOptions(readIdleTimeout = Some(Duration.Zero)).validate, invalid)
    assertEquals(PekkoOptions(readIdleTimeout = Some((-1).second)).validate, invalid)
