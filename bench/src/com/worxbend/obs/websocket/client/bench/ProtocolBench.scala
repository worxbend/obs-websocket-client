package com.worxbend.obs.websocket.client.bench

import com.worxbend.obs.websocket.client.protocol.*
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

/** Inbound envelope path: `Protocol.decode` (parse, `op` int, `d` object) per frame shape. */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(3)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
class ProtocolBench:
  @Param(Array("event", "response", "meters"))
  var frame: String = scala.compiletime.uninitialized

  private var text: String = scala.compiletime.uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    text = frame match
      case "event"    => Frames.event
      case "response" => Frames.response
      case "meters"   => Frames.meters
      case other      => throw IllegalArgumentException(s"unknown frame: $other")
    require(Protocol.decode(text = text).isRight, s"$frame frame must decode")

  @Benchmark
  def protocolDecode(bh: Blackhole): Unit = bh.consume(Protocol.decode(text = text))

/** Typed event decode of the 64-input `InputVolumeMeters` payload, envelope already parsed. */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(3)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
class EventDecodeBench:
  private var eventData: JsonObject = scala.compiletime.uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    eventData = Protocol.decode(text = Frames.meters).flatMap(_.data.obj(name = "eventData")).toOption.get
    require(Event.decode(eventType = "InputVolumeMeters", data = eventData).isRight, "meters event must decode")

  @Benchmark
  def metersEventDecode(bh: Blackhole): Unit =
    bh.consume(Event.decode(eventType = "InputVolumeMeters", data = eventData))

/** Deterministic obs-websocket 5.x frames. No randomness: every fork sees identical input. */
private[bench] object Frames:
  val event: String =
    """{"op":5,"d":{"eventType":"CurrentProgramSceneChanged","eventIntent":4,"eventData":{"sceneName":"main","sceneUuid":"2b8a4c6e-0f1d-4e2a-9b3c-5d7e9f1a3b5c"}}}"""

  val response: String =
    """{"op":7,"d":{"requestType":"GetVersion","requestId":"req-42","requestStatus":{"result":true,"code":100},"responseData":{"obsVersion":"31.0.0","obsWebSocketVersion":"5.6.3","rpcVersion":1,"platform":"linux","availableRequests":["GetVersion","GetStats","GetSceneList"]}}}"""

  /** 64 inputs, two channels each: the high-rate meter event (50 ms default cadence). */
  val meters: String =
    val input = (index: Int) =>
      val level = s"[0.${index % 10}25,0.${index % 10}5,0.${index % 10}75]"
      s"""{"inputName":"mic-$index","inputUuid":"00000000-0000-4000-8000-${"%012d".format(
          index
        )}","inputLevelsMul":[$level,$level]}"""
    val inputs = (0 until 64).map(input).mkString("[", ",", "]")
    s"""{"op":5,"d":{"eventType":"InputVolumeMeters","eventIntent":65536,"eventData":{"inputs":$inputs}}}"""
