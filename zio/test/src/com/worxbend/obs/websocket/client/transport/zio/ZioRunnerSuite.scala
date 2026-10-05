package com.worxbend.obs.websocket.client.transport.zio

import _root_.zio.ZIO
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import munit.FunSuite
import scala.concurrent.duration.*

class ZioRunnerSuite extends FunSuite:
  test("await returns the effect result"):
    assertEquals(ZioRunner.await(effect = ZIO.succeed(42)), 42)

  test("await rethrows effect failures unchanged"):
    val error  = new IOException("secret")
    val thrown = intercept[IOException]:
      ZioRunner.await(effect = ZIO.fail(error))
    assertEquals(thrown, error)

  test("a causeless ExecutionException failure is rethrown unchanged"):
    val error  = new java.util.concurrent.ExecutionException("bare", null)
    val thrown = intercept[java.util.concurrent.ExecutionException]:
      ZioRunner.await(effect = ZIO.fail(error))
    assertEquals(thrown, error)

  test("interruption cancels the fiber and propagates promptly"):
    val cancelled   = new AtomicBoolean(false)
    val interrupted = new AtomicBoolean(false)
    val effect      = ZIO.never.onInterrupt(_ => ZIO.succeed(cancelled.set(true)))
    val waiting     = new Thread(() =>
      try
        ZioRunner.await(effect = effect)
        ()
      catch case _: InterruptedException => interrupted.set(true)
    )
    waiting.start()
    Thread.sleep(200.millis.toMillis)
    waiting.interrupt()
    waiting.join(5000)
    assert(!waiting.isAlive, "Interrupted wait must finish promptly")
    assert(interrupted.get())
    val deadline = 2.seconds.fromNow
    while !cancelled.get() && deadline.hasTimeLeft() do Thread.sleep(5)
    assert(cancelled.get(), "Interruption must cancel the underlying ZIO fiber")
