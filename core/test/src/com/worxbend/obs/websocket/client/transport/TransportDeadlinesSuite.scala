package com.worxbend.obs.websocket.client.transport

import com.worxbend.obs.websocket.client.ObsError
import munit.FunSuite
import scala.concurrent.duration.*

class TransportDeadlinesSuite extends FunSuite:
  test("default write deadline is 10 seconds"):
    assertEquals(TransportDeadlines.DefaultWrite, 10.seconds)

  test("positive write and optional read-idle deadlines pass"):
    assertEquals(TransportDeadlines.validate(write = 1.milli, readIdle = None), Right(()))
    assertEquals(TransportDeadlines.validate(write = 1.milli, readIdle = Some(1.milli)), Right(()))

  test("non-positive write deadline is rejected first"):
    assertEquals(
      TransportDeadlines.validate(write = Duration.Zero, readIdle = Some(Duration.Zero)),
      Left(ObsError.InvalidConfiguration(message = "Write deadline must be positive")),
    )

  test("non-positive read-idle deadline is rejected"):
    assertEquals(
      TransportDeadlines.validate(write = 1.second, readIdle = Some((-1).seconds)),
      Left(ObsError.InvalidConfiguration(message = "Read idle deadline must be positive")),
    )
