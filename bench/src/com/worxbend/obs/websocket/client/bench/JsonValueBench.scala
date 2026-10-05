package com.worxbend.obs.websocket.client.bench

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.worxbend.obs.websocket.client.protocol.JsonValue
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

/** Decode+encode round-trip of the CURRENT `protocol.JsonValue` (builder-based decode) vs a private LEGACY copy
  * (per-key `Map.updated` + per-element `:+` decode, see [[LegacyJsonValue]]). Encoding is identical in both, so the
  * measured delta isolates the decode change.
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(3)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
class JsonValueBench:
  @Param(Array("small", "medium", "large"))
  var size: String = scala.compiletime.uninitialized

  private var bytes: Array[Byte] = scala.compiletime.uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    val document = size match
      case "small"  => JsonValueBench.smallDocument
      case "medium" => JsonValueBench.mediumDocument
      case "large"  => JsonValueBench.largeDocument
      case other    => throw IllegalArgumentException(s"unknown size: $other")
    bytes = document.getBytes(UTF_8)
    // Sanity check: both codecs must decode and re-render to identical output before any timing happens.
    val current = writeToString(readFromArray[JsonValue](bytes))
    val legacy  = writeToString(readFromArray[LegacyJsonValue](bytes))
    require(current == legacy, s"round-trip output disagreement on $size document")

  @Benchmark
  def currentRoundTrip(bh: Blackhole): Unit =
    bh.consume(writeToString(readFromArray[JsonValue](bytes)))

  @Benchmark
  def legacyRoundTrip(bh: Blackhole): Unit =
    bh.consume(writeToString(readFromArray[LegacyJsonValue](bytes)))

private object JsonValueBench:
  private def fields(entries: Seq[(String, String)]): String =
    entries.map((key, value) => s""""$key":$value""").mkString("{", ",", "}")

  private def leafObject(id: Int): String =
    fields(
      entries = Seq(
        "sceneItemId"      -> id.toString,
        "sceneItemEnabled" -> (id % 2 == 0).toString,
        "sourceName"       -> s""""capture-$id"""",
        "transform" -> fields(entries = Seq("x" -> (id * 3).toString, "y" -> (id * 7).toString, "scale" -> "1.5")),
        "tags"      -> (0 until 4).map(tag => s""""t${id}-$tag"""").mkString("[", ",", "]"),
      )
    )

  /** ~0.7 KB flat object with a short array. */
  val smallDocument: String =
    fields(
      entries = Seq(
        "op"         -> "5",
        "eventType"  -> """"SceneItemEnableStateChanged"""",
        "sceneName"  -> """"main"""",
        "items"      -> (1 to 4).map(leafObject).mkString("[", ",", "]"),
        "rpcVersion" -> "1",
        "negotiated" -> "true",
      )
    )

  /** ~4 KB: nested objects and arrays of numbers/strings. */
  val mediumDocument: String =
    fields(
      entries = Seq(
        "op"        -> "5",
        "eventData" -> fields(
          entries = Seq(
            "sceneItems" -> (1 to 16).map(leafObject).mkString("[", ",", "]"),
            "stats"      -> fields(
              entries = Seq(
                "cpu"     -> "12.5",
                "memory"  -> "2048.75",
                "fps"     -> "59.94",
                "dropped" -> "0",
              )
            ),
            "history" -> (1 to 24).map(index => s"""{"index":$index,"label":"event-$index"}""").mkString("[", ",", "]"),
          )
        ),
      )
    )

  /** ~60 KB: wide arrays of nested objects, standing in for a large batched event. */
  val largeDocument: String =
    fields(
      entries = Seq(
        "op"    -> "5",
        "batch" -> fields(
          entries = Seq(
            "items" -> (1 to 24)
              .map(group =>
                fields(entries =
                  Seq("group" -> group.toString, "entries" -> (1 to 32).map(leafObject).mkString("[", ",", "]"))
                )
              )
              .mkString("[", ",", "]"),
            "checksum" -> """"0123456789abcdef0123456789abcdef"""",
          )
        ),
      )
    )
