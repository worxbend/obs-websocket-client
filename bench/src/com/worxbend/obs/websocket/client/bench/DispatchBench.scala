package com.worxbend.obs.websocket.client.bench

import com.worxbend.obs.websocket.client.*
import com.worxbend.obs.websocket.client.protocol.*
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import ox.channels.Channel

/** One decoded event frame through `SessionLogic.received`, no actor: stats, diagnostics, typed decode, fan-out.
  * Subscriber and diagnostic channels are drained every op, so no buffer ever fills.
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(3)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
class DispatchBench:
  @Param(Array("0", "1", "8"))
  var subscribers: Int = 0

  @Param(Array("0", "1"))
  var diagnostics: Int = 0

  private var logic: SessionLogic                        = scala.compiletime.uninitialized
  private var message: WireMessage                       = scala.compiletime.uninitialized
  private var bytes: Int                                 = 0
  private var events: Array[Channel[Event]]              = scala.compiletime.uninitialized
  private var records: Array[Channel[SessionDiagnostic]] = scala.compiletime.uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    val outgoing   = Channel.buffered[WireMessage](4)
    val identified = Channel.buffered[Either[ObsError, ConnectionMetadata]](1)
    logic = new SessionLogic(
      config                 = ObsConfig(),
      authenticationPassword = None,
      outgoing               = outgoing,
      identified             = identified,
    )
    val hello = """{"op":0,"d":{"obsWebSocketVersion":"5.6.3","rpcVersion":1}}"""
    require(logic.incoming(message = Protocol.decode(text = hello).toOption.get), "hello must be accepted")
    val identifiedFrame = """{"op":2,"d":{"negotiatedRpcVersion":1}}"""
    require(logic.incoming(message = Protocol.decode(text = identifiedFrame).toOption.get), "identify must complete")
    require(logic.phase == ConnectionState.Ready, "session must be ready")
    events = Array.tabulate(subscribers): index =>
      val channel = Channel.buffered[Event](4)
      require(
        logic.subscribe(id = s"e$index", channel = channel, types = Set.empty, policy = OverflowPolicy.Fail).isRight
      )
      channel
    records = Array.tabulate(diagnostics): index =>
      val channel = Channel.buffered[SessionDiagnostic](4)
      require(logic.subscribeDiagnostics(id = s"d$index", channel = channel).isRight)
      channel
    message = Protocol.decode(text = Frames.event).toOption.get
    bytes   = Frames.event.length

  @Benchmark
  def received(bh: Blackhole): Unit =
    bh.consume(logic.received(bytes = bytes, decoded = Right(message)))
    var index = 0
    while index < events.length do
      bh.consume(events(index).tryReceiveOrClosed())
      index += 1
    index = 0
    while index < records.length do
      bh.consume(records(index).tryReceiveOrClosed())
      index += 1
