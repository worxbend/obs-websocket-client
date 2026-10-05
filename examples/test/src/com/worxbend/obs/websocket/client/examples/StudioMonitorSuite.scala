package com.worxbend.obs.websocket.client.examples

import com.worxbend.obs.websocket.client.protocol.Protocol
import com.worxbend.obs.websocket.client.protocol.events.InputVolumeMeters
import com.worxbend.obs.websocket.client.protocol.requests.GetStreamStatusResponse
import com.worxbend.obs.websocket.client.transport.sttp.{LocalWebSocketPeer, SttpObsClient, SttpOptions}
import com.worxbend.obs.websocket.client.{ObsConfig, ObsError}
import java.io.ByteArrayOutputStream
import java.net.Socket
import munit.FunSuite
import scala.collection.mutable
import scala.concurrent.duration.*

class StudioMonitorSuite extends FunSuite:
  private def greet(socket: Socket, availableRequests: String = "\"GetVersion\",\"GetStreamStatus\""): Unit =
    LocalWebSocketPeer.upgrade(socket = socket)
    LocalWebSocketPeer.send(socket = socket, text = """{"op":0,"d":{"obsWebSocketVersion":"5.7.0","rpcVersion":1}}""")
    val _ = LocalWebSocketPeer.receive(socket = socket) // Identify
    LocalWebSocketPeer.send(socket = socket, text = """{"op":2,"d":{"negotiatedRpcVersion":1}}""")
    // The internal capability probe ObsClient.run sends before invoking user code.
    val probe = Protocol.decode(text = LocalWebSocketPeer.receive(socket = socket)._2).toOption.get.data
    val id    = probe.string(name = "requestId").toOption.get
    LocalWebSocketPeer.send(
      socket = socket,
      text   =
        s"""{"op":7,"d":{"requestType":"GetVersion","requestId":"$id","requestStatus":{"result":true,"code":100},"responseData":{"obsVersion":"32.0","obsWebSocketVersion":"5.7.0","rpcVersion":1,"availableRequests":[$availableRequests],"supportedImageFormats":["png"],"platform":"linux","platformDescription":"test peer"}}}""",
    )

  private def sendEvent(socket: Socket, eventType: String, intent: Int, data: String): Unit =
    LocalWebSocketPeer.send(
      socket = socket,
      text   = s"""{"op":5,"d":{"eventType":"$eventType","eventIntent":$intent,"eventData":$data}}""",
    )

  /** Answer one GetStreamStatus. The client's baseline snapshot arrives right after the subscription goes live, so
    * answering it first also proves to this peer that subsequent events will be delivered, not dropped.
    */
  private def streamStatus(socket: Socket, result: Boolean, skipped: Int, total: Int): Unit =
    val request = Protocol.decode(text = LocalWebSocketPeer.receive(socket = socket)._2).toOption.get.data
    val kind    = request.string(name = "requestType").toOption.get
    val id      = request.string(name = "requestId").toOption.get
    assertEquals(kind, "GetStreamStatus")
    val status =
      if result then """{"result":true,"code":100}"""
      else
        """{"result":false,"code":702,"comment":"encoder overloaded"}""" // RequestProcessingFailed (comment required)
    val data =
      if result then
        s""","responseData":{"outputActive":true,"outputReconnecting":true,"outputTimecode":"00:00:10.000","outputDuration":10000,"outputCongestion":0.25,"outputBytes":123456,"outputSkippedFrames":$skipped,"outputTotalFrames":$total}"""
      else ""
    LocalWebSocketPeer.send(
      socket = socket,
      text   = s"""{"op":7,"d":{"requestType":"$kind","requestId":"$id","requestStatus":$status$data}}""",
    )

  private def expectClose(socket: Socket): Unit =
    assertEquals(LocalWebSocketPeer.receive(socket = socket)._1, 8)

  test("reacts to stream and record events and snapshots health only on transitions"):
    val lines                       = mutable.Buffer.empty[String]
    def serve(socket: Socket): Unit =
      greet(socket                = socket)
      streamStatus(socket, result = true, skipped = 0, total = 0) // baseline
      sendEvent(
        socket,
        "StreamStateChanged",
        64,
        """{"outputActive":true,"outputState":"OBS_WEBSOCKET_OUTPUT_STARTED"}""",
      )
      sendEvent(
        socket,
        "StreamStateChanged",
        64,
        """{"outputActive":true,"outputState":"OBS_WEBSOCKET_OUTPUT_RECONNECTING"}""",
      )
      streamStatus(socket, result = true, skipped = 30, total = 6000)
      sendEvent(
        socket,
        "RecordStateChanged",
        64,
        """{"outputActive":false,"outputState":"OBS_WEBSOCKET_OUTPUT_STOPPED","outputPath":"/videos/show.mkv"}""",
      )
      sendEvent(
        socket,
        "StreamStateChanged",
        64,
        """{"outputActive":false,"outputState":"OBS_WEBSOCKET_OUTPUT_STOPPED"}""",
      )
      streamStatus(socket, result = true, skipped = 40, total = 1000)
      sendEvent(socket, "ExitStarted", 1, """{}""")
      expectClose(socket = socket)
    val result = LocalWebSocketPeer.run(server = serve): uri =>
      StudioMonitor.run(config = ObsConfig(uri = uri), report = lines += _)
    assertEquals(result, Right(()))
    assertEquals(
      lines.toVector,
      Vector(
        "stream health: skipped 0 of 0 frames (0%), congestion 0.25, 123456 bytes sent",
        "stream: OBS_WEBSOCKET_OUTPUT_STARTED (active=true)",
        "stream: OBS_WEBSOCKET_OUTPUT_RECONNECTING (active=true)",
        "stream health: skipped 30 of 6000 frames (0.50%), congestion 0.25, 123456 bytes sent",
        "record: OBS_WEBSOCKET_OUTPUT_STOPPED (active=false) -> /videos/show.mkv",
        "stream: OBS_WEBSOCKET_OUTPUT_STOPPED (active=false)",
        "stream health: skipped 40 of 1000 frames (4.00%), congestion 0.25, 123456 bytes sent",
        "obs is shutting down",
      ),
    )

  test("reports a failed status snapshot without ending the monitor"):
    val lines                       = mutable.Buffer.empty[String]
    def serve(socket: Socket): Unit =
      greet(socket                = socket)
      streamStatus(socket, result = true, skipped = 0, total = 0) // baseline
      sendEvent(
        socket,
        "StreamStateChanged",
        64,
        """{"outputActive":true,"outputState":"OBS_WEBSOCKET_OUTPUT_RECONNECTING"}""",
      )
      streamStatus(socket, result = false, skipped = 0, total = 0)
      sendEvent(socket, "ExitStarted", 1, """{}""")
      expectClose(socket = socket)
    val result = LocalWebSocketPeer.run(server = serve): uri =>
      StudioMonitor.run(config = ObsConfig(uri = uri), report = lines += _)
    assertEquals(result, Right(()))
    assertEquals(lines(0), "stream health: skipped 0 of 0 frames (0%), congestion 0.25, 123456 bytes sent")
    assertEquals(lines(1), "stream: OBS_WEBSOCKET_OUTPUT_RECONNECTING (active=true)")
    assert(lines(2).startsWith("stream status unavailable: RequestRejected"), lines)
    assertEquals(lines.last, "obs is shutting down")
    assertEquals(lines.size, 4)

  test("snapshots degrade to log lines when the server does not advertise GetStreamStatus"):
    val lines                       = mutable.Buffer.empty[String]
    def serve(socket: Socket): Unit =
      greet(socket = socket, availableRequests = "\"GetVersion\"")
      // Snapshots are rejected locally by the capability gate, so no request ever reaches the wire and the
      // peer cannot observe when the subscription is live. Spaced repeats absorb that registration window:
      // early copies may be dropped, later ones are delivered, and each delivered event yields exactly one
      // event line plus one degraded-snapshot line.
      sendEvent(
        socket,
        "StreamStateChanged",
        64,
        s"""{"outputActive":true,"outputState":"${StudioMonitor.StreamReconnectingState}"}""",
      )
      Thread.sleep(200)
      sendEvent(
        socket,
        "StreamStateChanged",
        64,
        s"""{"outputActive":true,"outputState":"${StudioMonitor.StreamReconnectingState}"}""",
      )
      Thread.sleep(200)
      // A single ExitStarted suffices: the RECONNECTING repeats above already absorbed the registration
      // window, and the first delivered ExitStarted ends the monitor and tears down the connection.
      sendEvent(socket, "ExitStarted", 1, """{}""")
      expectClose(socket = socket)
    val result = LocalWebSocketPeer.run(server = serve): uri =>
      StudioMonitor.run(config = ObsConfig(uri = uri), report = lines += _)
    assertEquals(result, Right(()))
    assert(lines.head.startsWith("stream status unavailable: UnsupportedRequest"), lines)
    assertEquals(lines.last, "obs is shutting down")
    val middle      = lines.drop(1).dropRight(1)
    val streamLines = middle.filter(_.startsWith("stream: "))
    assert(streamLines.nonEmpty, lines)
    assert(
      streamLines.forall(_ == s"stream: ${StudioMonitor.StreamReconnectingState} (active=true)"),
      lines,
    )
    val unavailable = middle.filter(_.startsWith("stream status unavailable: UnsupportedRequest"))
    assertEquals(streamLines.size, unavailable.size, lines)
    assert(
      middle.forall(line =>
        line.startsWith("stream: ") || line.startsWith("stream status unavailable: UnsupportedRequest")
      ),
      lines,
    )

  test("a dead connection fails the monitor once the read-idle deadline passes"):
    val lines                       = mutable.Buffer.empty[String]
    def serve(socket: Socket): Unit =
      greet(socket                = socket)
      streamStatus(socket, result = true, skipped = 0, total = 0) // baseline
      sendEvent(
        socket,
        "StreamStateChanged",
        64,
        """{"outputActive":true,"outputState":"OBS_WEBSOCKET_OUTPUT_STARTED"}""",
      )
      // Hold the socket open but silent past the read-idle deadline: only liveness detection can end the
      // monitor. Closing here would let TCP EOF (Transport 1006) win the race every time and mask a
      // readIdleTimeout regression behind an always-green either-error assertion.
      Thread.sleep(4000)
    val result = LocalWebSocketPeer.run(server = serve): uri =>
      StudioMonitor.run(
        config  = ObsConfig(uri = uri),
        report  = lines += _,
        options = SttpOptions(readIdleTimeout = Some(2.seconds)),
      )
    assertEquals(
      lines.toVector,
      Vector(
        "stream health: skipped 0 of 0 frames (0%), congestion 0.25, 123456 bytes sent",
        "stream: OBS_WEBSOCKET_OUTPUT_STARTED (active=true)",
      ),
    )
    assert(
      result.left.toOption.exists {
        case _: ObsError.Timeout => true
        case _                   => false
      },
      result,
    )

  test("a server-initiated close surfaces the close code to the monitor"):
    val lines                       = mutable.Buffer.empty[String]
    def serve(socket: Socket): Unit =
      greet(socket                = socket)
      streamStatus(socket, result = true, skipped = 0, total = 0) // baseline
      // No event here: a close frame may legitimately overtake event delivery, and event rendering is
      // covered deterministically in the lifecycle test above. This test pins only close-code surfacing.
      LocalWebSocketPeer.sendClose(socket = socket, statusCode = 1000)
    val result = LocalWebSocketPeer.run(server = serve): uri =>
      StudioMonitor.run(config = ObsConfig(uri = uri), report = lines += _)
    assertEquals(
      lines.toVector,
      Vector("stream health: skipped 0 of 0 frames (0%), congestion 0.25, 123456 bytes sent"),
    )
    assert(
      result.left.toOption.exists {
        case ObsError.Transport(_, Some(1000)) => true
        case _                                 => false
      },
      result,
    )

  test("a client-side close ends the monitor cleanly"):
    val lines                       = mutable.Buffer.empty[String]
    val baselineSeen                = new java.util.concurrent.CountDownLatch(1)
    def serve(socket: Socket): Unit =
      greet(socket                = socket)
      streamStatus(socket, result = true, skipped = 0, total = 0) // baseline
      expectClose(socket          = socket)
    val result = LocalWebSocketPeer.run(server = serve): uri =>
      SttpObsClient.connect(config = ObsConfig(uri = uri)): session =>
        ox.supervised:
          val _ = ox.fork:
            // Bounded wait: if the monitor fails before its baseline report, close anyway after 5s
            // instead of parking this fork until the harness timeout masks the real failure.
            val _ = baselineSeen.await(5, java.util.concurrent.TimeUnit.SECONDS)
            session.close()
          StudioMonitor.monitor(
            session = session,
            report  = line =>
              lines += line
              baselineSeen.countDown(),
          )
    assertEquals(result, Right(Right(())))
    assertEquals(
      lines.toVector,
      Vector("stream health: skipped 0 of 0 frames (0%), congestion 0.25, 123456 bytes sent"),
    )

  test("CLI runs the monitor to clean completion without exiting the process"):
    def serve(socket: Socket): Unit =
      greet(socket                = socket)
      streamStatus(socket, result = true, skipped = 0, total = 0) // baseline
      sendEvent(socket, "ExitStarted", 1, """{}""")
      expectClose(socket = socket)
    val out = new ByteArrayOutputStream
    LocalWebSocketPeer.run(server = serve): uri =>
      Console.withOut(out):
        StudioMonitor.main(args = Array(uri))
    val rendered = out.toString("UTF-8")
    assert(rendered.contains("stream health: skipped 0 of 0 frames (0%)"), rendered)
    assert(rendered.contains("obs is shutting down"), rendered)

  test("CLI process exits unsuccessfully when configuration is invalid"):
    import java.nio.file.{Files, Paths}
    import java.util.concurrent.TimeUnit
    val classpath  = runtimeClasspath
    val output     = Files.createTempFile("obs-monitor-exit", ".log")
    val executable = Paths.get(System.getProperty("java.home"), "bin", "java").toString
    try
      val process = new ProcessBuilder(
        executable,
        "-cp",
        classpath,
        "com.worxbend.obs.websocket.client.examples.StudioMonitor",
        "invalid",
      )
        .redirectErrorStream(true)
        .redirectOutput(output.toFile)
        .start()
      try
        assert(process.waitFor(15, TimeUnit.SECONDS), "CLI process did not terminate within its test budget")
        assertEquals(process.exitValue(), 1)
        val diagnostic = Files.readString(output)
        assert(diagnostic.contains("OBS monitor failed: InvalidConfiguration"), diagnostic)
      finally
        val _ = process.destroyForcibly()
        val _ = process.waitFor(5, TimeUnit.SECONDS)
    finally Files.delete(output)

  test("status description guards an empty frame window"):
    val status = GetStreamStatusResponse(
      outputActive        = false,
      outputReconnecting  = false,
      outputTimecode      = "00:00:00.000",
      outputDuration      = BigDecimal(0),
      outputCongestion    = BigDecimal(0),
      outputBytes         = BigDecimal(0),
      outputSkippedFrames = BigDecimal(0),
      outputTotalFrames   = BigDecimal(0),
    )
    assertEquals(
      StudioMonitor.describeStatus(status = status),
      "stream health: skipped 0 of 0 frames (0%), congestion 0, 0 bytes sent",
    )

  test("event description ignores events outside the watched set"):
    assertEquals(StudioMonitor.describe(event = InputVolumeMeters(inputs = Vector.empty)), None)

  test("snapshot worthiness follows the stream state"):
    assert(StudioMonitor.snapshotWorthy(state = StudioMonitor.StreamReconnectingState))
    assert(StudioMonitor.snapshotWorthy(state = StudioMonitor.StreamStoppedState))
    assert(!StudioMonitor.snapshotWorthy(state = "OBS_WEBSOCKET_OUTPUT_STARTED"))

  test("CLI reports invalid input without attempting a socket"):
    val out = new ByteArrayOutputStream
    val err = new ByteArrayOutputStream
    // Console capture mutates global streams; safe because munit runs tests sequentially by default.
    // Do not enable parallel test execution for this suite.
    Console.withOut(out):
      Console.withErr(err):
        assertEquals(StudioMonitor.execute(args = Array("invalid"), environment = Map.empty), 1)
    assertEquals(out.toString("UTF-8"), "")
    val failure = err.toString("UTF-8")
    assert(failure.startsWith("OBS monitor failed: "), failure)
    assert(failure.contains("InvalidConfiguration"), failure)

  test("configuration accepts explicit URL and password"):
    val config = StudioMonitor.configuration(
      args        = Array("ws://example.test:4455"),
      environment = Map("OBS_WS_PASSWORD" -> "secret"),
    )
    assertEquals(config.uri, "ws://example.test:4455")
    assertEquals(config.passwordProvider.password(), Right(Some("secret")))

  test("configuration defaults to local OBS with no credentials"):
    val config = StudioMonitor.configuration(args = Array.empty, environment = Map.empty)
    assertEquals(config.uri, "ws://localhost:4455")
    assertEquals(config.passwordProvider.password(), Right(None))

  test("configuration treats a blank password as absent"):
    val config = StudioMonitor.configuration(args = Array.empty, environment = Map("OBS_WS_PASSWORD" -> "  "))
    assertEquals(config.passwordProvider.password(), Right(None))

  private def runtimeClasspath: String =
    val loaders = Iterator
      .iterate(Option(getClass.getClassLoader))(_.flatMap(loader => Option(loader.getParent)))
      .takeWhile(_.nonEmpty)
      .flatMap(_.iterator)
    val urls = loaders
      .collect { case loader: java.net.URLClassLoader => loader }
      .flatMap(_.getURLs)
      .map(url => java.nio.file.Paths.get(url.toURI).toString)
    // java.class.path is the fallback for classloader setups with no URLClassLoader in the chain
    // (some build-tool test runners); there it carries the full test classpath instead.
    (urls ++ System.getProperty("java.class.path").split(java.io.File.pathSeparator)).toVector.distinct
      .mkString(java.io.File.pathSeparator)
