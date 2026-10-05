package com.worxbend.obs.websocket.client.transport.zio

import com.worxbend.obs.websocket.client.ObsError
import munit.FunSuite
import scala.concurrent.duration.*

class ZioOptionsSuite extends FunSuite:
  test("write deadline defaults and renders no header values"):
    val options = ZioOptions()
    assertEquals(options.validate, Right(options))
    assertEquals(options.toString, "ZioOptions(writeTimeout=10 seconds, headers=<redacted>, readIdleTimeout=None)")
    val invalid = ZioOptions(writeTimeout = Duration.Zero)
    assertEquals(invalid.validate, Left(ObsError.InvalidConfiguration(message = "Write deadline must be positive")))

  test("read idle deadline is opt-in and validates positivity"):
    assertEquals(ZioOptions().readIdleTimeout, None)
    val liveness = ZioOptions(readIdleTimeout = Some(30.seconds))
    assertEquals(liveness.validate, Right(liveness))
    assert(liveness.toString.contains("readIdleTimeout=Some(30 seconds)"))
    val invalid = Left(ObsError.InvalidConfiguration(message = "Read idle deadline must be positive"))
    assertEquals(ZioOptions(readIdleTimeout = Some(Duration.Zero)).validate, invalid)
    assertEquals(ZioOptions(readIdleTimeout = Some((-1).second)).validate, invalid)
