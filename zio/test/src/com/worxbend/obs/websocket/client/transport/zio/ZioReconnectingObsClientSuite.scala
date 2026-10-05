package com.worxbend.obs.websocket.client.transport.zio

import com.worxbend.obs.websocket.client.protocol.Protocol
import com.worxbend.obs.websocket.client.reconnect.{ReconnectDecision, ReconnectPolicy}
import com.worxbend.obs.websocket.client.transport.sttp.LocalWebSocketPeer
import com.worxbend.obs.websocket.client.{ObsConfig, ObsError}
import munit.FunSuite
import scala.concurrent.duration.*

class ZioReconnectingObsClientSuite extends FunSuite:
  private val policy = ReconnectPolicy
    .create(maxRetries = 3, initialDelay = 1.millis, maxDelay = 4.millis, jitterFraction = 0.0)
    .toOption
    .get

  test("opt-in public entrypoint owns a real ZIO connection with defaults"):
    def serve(socket: java.net.Socket): Unit =
      LocalWebSocketPeer.upgrade(socket = socket)
      LocalWebSocketPeer.send(socket = socket, text = """{"op":0,"d":{"obsWebSocketVersion":"5.7.0","rpcVersion":1}}""")
      assertEquals(Protocol.decode(text = LocalWebSocketPeer.receive(socket = socket)._2).toOption.get.op, 1)
      LocalWebSocketPeer.send(socket = socket, text = """{"op":2,"d":{"negotiatedRpcVersion":1}}""")
      val request = Protocol.decode(text = LocalWebSocketPeer.receive(socket = socket)._2).toOption.get
      val id      = request.data.string(name = "requestId").toOption.get
      LocalWebSocketPeer.send(
        socket = socket,
        text   =
          s"""{"op":7,"d":{"requestType":"GetVersion","requestId":"$id","requestStatus":{"result":true,"code":100},"responseData":{"availableRequests":[]}}}""",
      )
      assertEquals(LocalWebSocketPeer.receive(socket = socket)._1, 8)
    val result = LocalWebSocketPeer.run(server = serve): uri =>
      ZioReconnectingObsClient.run(config = ObsConfig(uri = uri), policy = policy): (generation, _) =>
        ReconnectDecision.Complete(value = generation.value)
    assertEquals(result, Right(1L))

  test("invalid transport options validate before connecting"):
    val invalid = ZioOptions(writeTimeout = Duration.Zero)
    assertEquals(
      ZioReconnectingObsClient.run(
        config  = ObsConfig(),
        policy  = policy,
        options = invalid,
      )((_, _) => ReconnectDecision.Complete(value = ())),
      Left(ObsError.InvalidConfiguration(message = "Write deadline must be positive")),
    )
