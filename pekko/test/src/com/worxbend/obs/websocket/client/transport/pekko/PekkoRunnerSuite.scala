package com.worxbend.obs.websocket.client.transport.pekko

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import munit.FunSuite
import scala.concurrent.{Future, Promise}

class PekkoRunnerSuite extends FunSuite:
  test("await returns the future result"):
    assertEquals(PekkoRunner.await(future = Future.successful(42)), 42)

  test("await rethrows failures unchanged"):
    val error  = new IOException("secret")
    val thrown = intercept[IOException]:
      PekkoRunner.await(future = Future.failed(error))
    assertEquals(thrown, error)

  test("a causeless ExecutionException failure is rethrown unchanged"):
    val error  = new java.util.concurrent.ExecutionException("bare", null)
    val thrown = intercept[java.util.concurrent.ExecutionException]:
      PekkoRunner.await(future = Future.failed(error))
    assertEquals(thrown, error)

  test("fatal failures boxed by scala Promise are unboxed"):
    val error = new InterruptedException("teardown")
    // munit's intercept only catches non-fatal exceptions, so catch directly.
    val thrown =
      try
        PekkoRunner.await(future = Promise[Unit]().failure(error).future)
        throw new IllegalStateException("expected interruption")
      catch case caught: InterruptedException => caught
    assertEquals(thrown, error)

  test("interruption of a blocked wait propagates promptly"):
    val _           = Thread.interrupted()
    val interrupted = new AtomicBoolean(false)
    val waiting     = new Thread(() =>
      try
        PekkoRunner.await(future = Promise[Unit]().future)
        ()
      catch case _: InterruptedException => interrupted.set(true)
    )
    waiting.start()
    Thread.sleep(200)
    waiting.interrupt()
    waiting.join(5000)
    assert(!waiting.isAlive, "Interrupted wait must finish promptly")
    assert(interrupted.get())
