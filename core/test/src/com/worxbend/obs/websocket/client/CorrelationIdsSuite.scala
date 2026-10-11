package com.worxbend.obs.websocket.client

import munit.FunSuite
import ox.*

class CorrelationIdsSuite extends FunSuite:
  test("repeated and delimiter-containing seeds still issue distinct ordinals"):
    val ids = new CorrelationIds
    assertEquals(ids.next(seed = "seed:2"), Right("seed:2:1"))
    assertEquals(ids.next(seed = "seed:2"), Right("seed:2:2"))

  test("exhausted counter remains exhausted rather than wrapping or reissuing"):
    val ids = new CorrelationIds(initialOrdinal = Long.MaxValue - 2L)
    assertEquals(ids.next(seed = "seed"), Right(s"seed:${Long.MaxValue - 1L}"))
    val exhausted = Left(ObsError.InternalError(message = "Connection correlation IDs exhausted"))
    assertEquals(ids.next(seed = "seed"), exhausted)
    assertEquals(ids.next(seed = "another"), exhausted)

  test("concurrent operations share a unique connection-local counter"):
    supervised:
      val ids    = new CorrelationIds
      val calls  = Vector.fill(32)(fork(ids.next(seed = "same")))
      val issued = calls.map(_.join())
      assert(issued.forall(_.isRight))
      assertEquals(issued.toSet.size, calls.size)
