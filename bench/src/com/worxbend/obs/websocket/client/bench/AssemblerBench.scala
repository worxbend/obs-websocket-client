package com.worxbend.obs.websocket.client.bench

import com.worxbend.obs.websocket.client.transport.MessageAssembler
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

/** One single-fragment text message through a fresh `MessageAssembler`, as each transport read does. */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(3)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
class AssemblerBench:
  @Param(Array("1024", "4194304"))
  var size: Int = 0

  private var payload: String = scala.compiletime.uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    payload = "x" * size
    val assembled = new MessageAssembler(maxMessageBytes = 16 * 1024 * 1024)
      .appendFragment(payload = payload, finalFragment = true)
    require(assembled == Right(Some(payload)), "single fragment must assemble to itself")

  @Benchmark
  def singleFragment(bh: Blackhole): Unit =
    bh.consume(
      new MessageAssembler(maxMessageBytes = 16 * 1024 * 1024).appendFragment(payload = payload, finalFragment = true)
    )
