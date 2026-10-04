package com.worxbend.obs.websocket.client.bench

import com.worxbend.obs.websocket.client.util.Utf8
import java.nio.{ByteBuffer, CharBuffer}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.charset.{CharsetEncoder, CodingErrorAction}
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

/** NEW (arithmetic `Utf8.encodedLength`) vs the two LEGACY byte-counting implementations it replaced:
  * `String.getBytes(UTF_8).length` (materializes the whole encoded array) and a per-call `CharsetEncoder` with a 4 KiB
  * scratch buffer (verbatim copy of the pre-optimization `SttpTransport.utf8ByteLength` send path, commit 7185fb1).
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(3)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
class Utf8Bench:
  @Param(Array("shortAscii", "obsMessage", "largeAscii", "cjkEmoji"))
  var payloadName: String = scala.compiletime.uninitialized

  private var text: String = scala.compiletime.uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    text = payloadName match
      case "shortAscii" => Payloads.shortAscii
      case "obsMessage" => Payloads.obsMessage
      case "largeAscii" => Payloads.largeAscii
      case "cjkEmoji"   => Payloads.cjkEmoji
      case other        => throw IllegalArgumentException(s"unknown payload: $other")
    // Sanity check: the three implementations must agree on every payload before any timing happens.
    val arithmetic = Utf8.encodedLength(text)
    val getBytes = LegacyUtf8.getBytesLength(text)
    val encoder = LegacyUtf8.encoderLength(LegacyUtf8.newEncoder(), ByteBuffer.allocate(LegacyUtf8.scratchBytes), text)
    require(
      arithmetic == getBytes && arithmetic == encoder,
      s"UTF-8 length disagreement on $payloadName: arithmetic=$arithmetic getBytes=$getBytes encoder=$encoder"
    )

  @Benchmark
  def arithmeticNew(bh: Blackhole): Unit = bh.consume(Utf8.encodedLength(text))

  @Benchmark
  def legacyGetBytes(bh: Blackhole): Unit = bh.consume(LegacyUtf8.getBytesLength(text))

  @Benchmark
  def legacyEncoderScratch(bh: Blackhole): Unit =
    bh.consume(LegacyUtf8.encoderLength(LegacyUtf8.newEncoder(), ByteBuffer.allocate(LegacyUtf8.scratchBytes), text))

/** Verbatim copies of the legacy implementations, kept private to the benchmark for A/B comparison. */
private[bench] object LegacyUtf8:
  val scratchBytes = 4096

  def getBytesLength(text: String): Long = text.getBytes(UTF_8).length.toLong

  def newEncoder(): CharsetEncoder =
    UTF_8
      .newEncoder()
      .onMalformedInput(CodingErrorAction.REPLACE)
      .onUnmappableCharacter(CodingErrorAction.REPLACE)

  /** Exact copy of the removed `SttpTransport.utf8ByteLength`: reusable encoder and scratch buffer instead of
    * materializing the encoded array.
    */
  def encoderLength(encoder: CharsetEncoder, scratch: ByteBuffer, value: String): Long =
    encoder.reset()
    val in = CharBuffer.wrap(value)
    var bytes = 0L
    var encoding = true
    while encoding do
      scratch.clear()
      val result = encoder.encode(in, scratch, true)
      bytes += scratch.position()
      encoding = result.isOverflow
    scratch.clear()
    val _ = encoder.flush(scratch)
    bytes + scratch.position()
