package com.worxbend.obs.websocket.client.transport.fs2

import com.worxbend.obs.websocket.client.ObsError
import munit.FunSuite
import scala.concurrent.duration.*

class Fs2OptionsSuite extends FunSuite:
  test("write deadline defaults and renders no header values"):
    val options = Fs2Options()
    assertEquals(options.validate, Right(options))
    assertEquals(options.toString, "Fs2Options(writeTimeout=10 seconds, headers=<redacted>, readIdleTimeout=None)")
    val invalid = Fs2Options(writeTimeout = Duration.Zero)
    assertEquals(invalid.validate, Left(ObsError.InvalidConfiguration(message = "Write deadline must be positive")))

  test("read idle deadline is opt-in and validates positivity"):
    assertEquals(Fs2Options().readIdleTimeout, None)
    val liveness = Fs2Options(readIdleTimeout = Some(30.seconds))
    assertEquals(liveness.validate, Right(liveness))
    assert(liveness.toString.contains("readIdleTimeout=Some(30 seconds)"))
    val invalid = Left(ObsError.InvalidConfiguration(message = "Read idle deadline must be positive"))
    assertEquals(Fs2Options(readIdleTimeout = Some(Duration.Zero)).validate, invalid)
    assertEquals(Fs2Options(readIdleTimeout = Some((-1).second)).validate, invalid)
