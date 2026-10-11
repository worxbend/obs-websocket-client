package com.worxbend.obs.websocket.client.transport.fs2

import cats.effect.IO
import java.io.IOException
import java.util.concurrent.{CancellationException, CountDownLatch, ExecutionException, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean
import scala.util.{Failure, Success}
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

  test("an independently canceled effect publishes completion after its finalizer"):
    val finalized = new AtomicBoolean(false)
    val effect    = (IO.canceled *> IO.never[Int]).onCancel(IO(finalized.set(true)))
    val result    = ox.timeoutOption(2.seconds):
      intercept[CancellationException]:
        Fs2Runner.await(effect = effect)
    assert(result.nonEmpty, "Independent cancellation must finish without interrupting the waiter")
    assert(finalized.get())

  test("an ordinary wrapped failure is not mistaken for Promise fatal boxing"):
    val cause  = new IOException("ordinary cause")
    val error  = new ExecutionException(cause)
    val thrown = intercept[ExecutionException]:
      Fs2Runner.await(effect = IO.raiseError(error))
    assert(thrown eq error)
    assert(thrown.getCause eq cause)

  test("only the outer fatal failure channel is unboxed"):
    val fatal  = new LinkageError("fatal")
    val thrown =
      try
        val _ = Fs2Runner.completed[Int](Failure(new ExecutionException(fatal)))
        None
      catch case error: LinkageError => Some(error)
    assert(thrown.exists(_ eq fatal))
    val wrapped  = new ExecutionException(fatal)
    val ordinary = intercept[ExecutionException]:
      Fs2Runner.completed[Int](Success(Left(wrapped)))
    assert(ordinary eq wrapped)

  test("interruption does not wait for native cancellation finalizers"):
    val started     = new CountDownLatch(1)
    val finalizing  = new CountDownLatch(1)
    val release     = new CountDownLatch(1)
    val finalized   = new CountDownLatch(1)
    val interrupted = new AtomicBoolean(false)
    val effect      = (IO(started.countDown()) *> IO.never[Int]).onCancel(
      IO(finalizing.countDown()) *> IO.blocking(release.await()) *> IO(finalized.countDown())
    )
    val waiting = new Thread(() =>
      try
        val _ = Fs2Runner.await(effect = effect)
      catch case _: InterruptedException => interrupted.set(true)
    )
    waiting.start()
    try
      assert(started.await(2, TimeUnit.SECONDS))
      waiting.interrupt()
      assert(finalizing.await(2, TimeUnit.SECONDS))
      waiting.join(2000)
      assert(!waiting.isAlive, "The waiter must not join a foreign cancellation finalizer")
      assert(interrupted.get())
    finally
      release.countDown()
      waiting.interrupt()
      waiting.join(2000)
    assert(finalized.await(2, TimeUnit.SECONDS))
    assertEquals(Fs2Runner.await(effect = IO.pure(7)), 7)

  test("a late masked success cannot retain or overwrite an interrupted waiter"):
    val started     = new CountDownLatch(1)
    val release     = new CountDownLatch(1)
    val finalized   = new CountDownLatch(1)
    val interrupted = new AtomicBoolean(false)
    val effect      = IO.uncancelable: _ =>
      (IO(started.countDown()) *> IO.blocking(release.await()).as(42)).guarantee(IO(finalized.countDown()))
    val waiting = new Thread(() =>
      try
        val _ = Fs2Runner.await(effect = effect)
      catch case _: InterruptedException => interrupted.set(true)
    )
    waiting.start()
    try
      assert(started.await(2, TimeUnit.SECONDS))
      waiting.interrupt()
      waiting.join(2000)
      assert(!waiting.isAlive)
      assert(interrupted.get())
    finally
      release.countDown()
      waiting.interrupt()
      waiting.join(2000)
    assert(finalized.await(2, TimeUnit.SECONDS))
    assertEquals(Fs2Runner.await(effect = IO.pure(99)), 99)

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
