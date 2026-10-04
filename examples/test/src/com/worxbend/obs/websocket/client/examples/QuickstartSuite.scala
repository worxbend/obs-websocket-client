package com.worxbend.obs.websocket.client.examples

import com.worxbend.obs.websocket.client.ObsConfig
import com.worxbend.obs.websocket.client.protocol.Protocol
import com.worxbend.obs.websocket.client.transport.sttp.LocalWebSocketPeer
import java.io.ByteArrayOutputStream
import java.net.Socket
import munit.FunSuite

class QuickstartSuite extends FunSuite:
  private def serve(socket: Socket): Unit =
    LocalWebSocketPeer.upgrade(socket)
    LocalWebSocketPeer.send(socket, """{"op":0,"d":{"obsWebSocketVersion":"5.7.0","rpcVersion":1}}""")
    val _ = LocalWebSocketPeer.receive(socket)
    LocalWebSocketPeer.send(socket, """{"op":2,"d":{"negotiatedRpcVersion":1}}""")
    for _ <- 1 to 3 do
      val request = Protocol.decode(LocalWebSocketPeer.receive(socket)._2).toOption.get.data
      val kind = request.string("requestType").toOption.get
      val id = request.string("requestId").toOption.get
      val data = if kind == "GetVersion" then
        """{"obsVersion":"32.0","obsWebSocketVersion":"5.7.0","rpcVersion":1,"availableRequests":["GetVersion","GetSceneList"],"supportedImageFormats":["png"],"platform":"linux","platformDescription":"test peer"}"""
      else
        """{"currentProgramSceneName":null,"currentProgramSceneUuid":null,"currentPreviewSceneName":null,"currentPreviewSceneUuid":null,"scenes":[]}"""
      LocalWebSocketPeer.send(
        socket,
        s"""{"op":7,"d":{"requestType":"$kind","requestId":"$id","requestStatus":{"result":true,"code":100},"responseData":$data}}"""
      )
    assertEquals(LocalWebSocketPeer.receive(socket)._1, 8)

  test("read-only discovery returns version and scene catalog"):
    val result = LocalWebSocketPeer.run(serve): uri =>
      Quickstart.run(ObsConfig(uri = uri))
    assertEquals(result.toOption.get._1.obsVersion, "32.0")
    assertEquals(result.toOption.get._2.scenes.size, 0)

  test("CLI reports discovery without mutating OBS"):
    val out = new ByteArrayOutputStream
    LocalWebSocketPeer.run(serve): uri =>
      Console.withOut(out):
        Quickstart.main(Array(uri))
    assertEquals(out.toString("UTF-8"), s"OBS 32.0: 0 scenes${System.lineSeparator}")

  test("CLI reports invalid input without attempting a socket"):
    val out = new ByteArrayOutputStream
    val err = new ByteArrayOutputStream
    Console.withOut(out):
      Console.withErr(err):
        Quickstart.main(Array("invalid"))
    assertEquals(out.toString("UTF-8"), "")
    val failure = err.toString("UTF-8")
    assert(failure.startsWith("OBS connection failed: "), failure)
    assert(failure.contains("InvalidConfiguration"), failure)

  test("configuration accepts explicit URL and password"):
    val config = Quickstart.configuration(Array("ws://example.test:4455"), Map("OBS_WS_PASSWORD" -> "secret"))
    assertEquals(config.uri, "ws://example.test:4455")
    assertEquals(config.passwordProvider.password(), Right(Some("secret")))

  test("configuration defaults to local OBS with no credentials"):
    val config = Quickstart.configuration(Array.empty, Map.empty)
    assertEquals(config.uri, "ws://localhost:4455")
    assertEquals(config.passwordProvider.password(), Right(None))
