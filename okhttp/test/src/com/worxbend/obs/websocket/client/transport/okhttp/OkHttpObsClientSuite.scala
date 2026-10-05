package com.worxbend.obs.websocket.client.transport.okhttp

import com.worxbend.obs.websocket.client.protocol.Protocol
import com.worxbend.obs.websocket.client.transport.sttp.{LocalWebSocketPeer, SttpOptions}
import com.worxbend.obs.websocket.client.{ObsConfig, ObsError}
import munit.FunSuite
import scala.concurrent.duration.*

/** okhttp-specific client cases; the cross-backend wire contract lives in [[OkHttpContractSuite]]. */
class OkHttpObsClientSuite extends FunSuite:
  test("read idle deadline aborts a stalled connection and still releases TCP"):
    def serve(socket: java.net.Socket): Unit =
      LocalWebSocketPeer.upgrade(socket = socket)
      LocalWebSocketPeer.send(socket = socket, text = """{"op":0,"d":{"obsWebSocketVersion":"5.7.0","rpcVersion":1}}""")
      assertEquals(Protocol.decode(text = LocalWebSocketPeer.receive(socket = socket)._2).toOption.get.op, 1)
      LocalWebSocketPeer.send(socket = socket, text = """{"op":2,"d":{"negotiatedRpcVersion":1}}""")
      assertEquals(Protocol.decode(text = LocalWebSocketPeer.receive(socket = socket)._2).toOption.get.op, 6)
      assertEquals(socket.getInputStream.read(), -1, "A force-aborted connection must still release TCP")
    val options = SttpOptions(readIdleTimeout = Some(100.millis))
    val result  = LocalWebSocketPeer.run(server = serve): uri =>
      OkHttpObsClient.connect(config = ObsConfig(uri = uri), options = options)(use =
        _.rawRequest(requestType = "GetVersion")
      )
    assertEquals(result, Left(ObsError.Timeout(operation = "read")))

  test("reconnect entrypoint validates transport options before connecting"):
    val policy = com.worxbend.obs.websocket.client.reconnect.ReconnectPolicy.create(maxRetries = 0).toOption.get
    assertEquals(
      OkHttpReconnectingObsClient.run(
        config  = ObsConfig(),
        policy  = policy,
        options = SttpOptions(writeTimeout = Duration.Zero),
      )((_, _) => com.worxbend.obs.websocket.client.reconnect.ReconnectDecision.Complete(value = ())),
      Left(ObsError.InvalidConfiguration(message = "Write deadline must be positive")),
    )
