package com.worxbend.obs.websocket.client.examples

import com.worxbend.obs.websocket.client.ObsConfig
import com.worxbend.obs.websocket.client.protocol.Protocol
import com.worxbend.obs.websocket.client.transport.sttp.LocalWebSocketPeer
import java.io.ByteArrayOutputStream
import java.net.Socket
import munit.FunSuite

class QuickstartSuite extends FunSuite:
  private def serve(socket: Socket): Unit =
    LocalWebSocketPeer.upgrade(socket = socket)
    LocalWebSocketPeer.send(socket = socket, text = """{"op":0,"d":{"obsWebSocketVersion":"5.7.0","rpcVersion":1}}""")
    val _ = LocalWebSocketPeer.receive(socket = socket)
    LocalWebSocketPeer.send(socket = socket, text = """{"op":2,"d":{"negotiatedRpcVersion":1}}""")
    // Three requests arrive in order: the internal GetVersion capability probe that ObsClient.run sends before
    // invoking user code, then Quickstart's own GetVersion and GetSceneList. The probe has no shared named
    // constant; removing it or the discovery requests requires adjusting this count.
    for _ <- 1 to 3 do
      val request = Protocol.decode(text = LocalWebSocketPeer.receive(socket = socket)._2).toOption.get.data
      val kind    = request.string(name = "requestType").toOption.get
      val id      = request.string(name = "requestId").toOption.get
      val data    = if kind == "GetVersion" then
        """{"obsVersion":"32.0","obsWebSocketVersion":"5.7.0","rpcVersion":1,"availableRequests":["GetVersion","GetSceneList"],"supportedImageFormats":["png"],"platform":"linux","platformDescription":"test peer"}"""
      else
        """{"currentProgramSceneName":null,"currentProgramSceneUuid":null,"currentPreviewSceneName":null,"currentPreviewSceneUuid":null,"scenes":[]}"""
      LocalWebSocketPeer.send(
        socket = socket,
        text   =
          s"""{"op":7,"d":{"requestType":"$kind","requestId":"$id","requestStatus":{"result":true,"code":100},"responseData":$data}}""",
      )
    assertEquals(LocalWebSocketPeer.receive(socket = socket)._1, 8)

  test("read-only discovery returns version and scene catalog"):
    val result = LocalWebSocketPeer.run(server = serve): uri =>
      Quickstart.run(config = ObsConfig(uri = uri))
    assertEquals(result.toOption.get._1.obsVersion, "32.0")
    assertEquals(result.toOption.get._2.scenes.size, 0)

  test("CLI reports discovery without mutating OBS"):
    val out = new ByteArrayOutputStream
    LocalWebSocketPeer.run(server = serve): uri =>
      Console.withOut(out):
        Quickstart.main(args = Array(uri))
    assertEquals(out.toString("UTF-8"), s"OBS 32.0: 0 scenes${System.lineSeparator}")

  test("CLI reports invalid input without attempting a socket"):
    val out = new ByteArrayOutputStream
    val err = new ByteArrayOutputStream
    Console.withOut(out):
      Console.withErr(err):
        assertEquals(Quickstart.execute(args = Array("invalid"), environment = Map.empty), 1)
    assertEquals(out.toString("UTF-8"), "")
    val failure = err.toString("UTF-8")
    assert(failure.startsWith("OBS connection failed: "), failure)
    assert(failure.contains("InvalidConfiguration"), failure)

  test("configuration accepts explicit URL and password"):
    val config =
      Quickstart.configuration(args = Array("ws://example.test:4455"), environment = Map("OBS_WS_PASSWORD" -> "secret"))
    assertEquals(config.uri, "ws://example.test:4455")
    assertEquals(config.passwordProvider.password(), Right(Some("secret")))

  test("configuration defaults to local OBS with no credentials"):
    val config = Quickstart.configuration(args = Array.empty, environment = Map.empty)
    assertEquals(config.uri, "ws://localhost:4455")
    assertEquals(config.passwordProvider.password(), Right(None))

  test("configuration treats a blank password as absent"):
    val config = Quickstart.configuration(args = Array.empty, environment = Map("OBS_WS_PASSWORD" -> "  "))
    assertEquals(config.passwordProvider.password(), Right(None))

  test("CLI process exits unsuccessfully when configuration is invalid"):
    import java.nio.file.{Files, Paths}
    import java.util.concurrent.TimeUnit
    val classpath  = runtimeClasspath
    val output     = Files.createTempFile("obs-quickstart-exit", ".log")
    val executable = Paths.get(System.getProperty("java.home"), "bin", "java").toString
    val process    = new ProcessBuilder(
      executable,
      "-cp",
      classpath,
      "com.worxbend.obs.websocket.client.examples.Quickstart",
      "invalid",
    )
      .redirectErrorStream(true)
      .redirectOutput(output.toFile)
      .start()
    try
      assert(process.waitFor(15, TimeUnit.SECONDS), "CLI process did not terminate within its test budget")
      assertEquals(process.exitValue(), 1)
      val diagnostic = Files.readString(output)
      assert(diagnostic.contains("OBS connection failed: InvalidConfiguration"), diagnostic)
    finally
      val _ = process.destroyForcibly()
      val _ = process.waitFor(5, TimeUnit.SECONDS)
      Files.delete(output)

  private def runtimeClasspath: String =
    val loaders = Iterator
      .iterate(Option(getClass.getClassLoader))(_.flatMap(loader => Option(loader.getParent)))
      .takeWhile(_.nonEmpty)
      .flatMap(_.iterator)
    val urls = loaders
      .collect { case loader: java.net.URLClassLoader => loader }
      .flatMap(_.getURLs)
      .map(url => java.nio.file.Paths.get(url.toURI).toString)
    (urls ++ System.getProperty("java.class.path").split(java.io.File.pathSeparator)).toVector.distinct
      .mkString(java.io.File.pathSeparator)
