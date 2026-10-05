package com.worxbend.obs.websocket.client.transport.fs2

import cats.effect.IO
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import munit.FunSuite
import scala.concurrent.duration.*

class Fs2RunnerSuite extends FunSuite:
  test("await returns the effect result"):
    assertEquals(Fs2Runner.await(effect = IO.pure(42)), 42)

  test("await rethrows effect failures unchanged"):
    val error  = new IOException("secret")
    val thrown = intercept[IOException]:
      Fs2Runner.await(effect = IO.raiseError(error))
    assertEquals(thrown, error)

  test("a causeless ExecutionException failure is rethrown unchanged"):
    val error  = new java.util.concurrent.ExecutionException("bare", null)
    val thrown = intercept[java.util.concurrent.ExecutionException]:
      Fs2Runner.await(effect = IO.raiseError(error))
    assertEquals(thrown, error)

  test("interruption cancels the fiber and propagates promptly"):
    val cancelled   = new AtomicBoolean(false)
    val interrupted = new AtomicBoolean(false)
    val effect      = IO.never.onCancel(IO(cancelled.set(true)).void)
    val waiting     = new Thread(() =>
      try
        Fs2Runner.await(effect = effect)
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
    assert(cancelled.get(), "Interruption must cancel the underlying cats-effect fiber")
