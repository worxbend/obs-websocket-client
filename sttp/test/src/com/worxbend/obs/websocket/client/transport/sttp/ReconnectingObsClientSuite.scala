package com.worxbend.obs.websocket.client.transport.sttp

import com.worxbend.obs.websocket.client.protocol.{Event, Protocol}
import com.worxbend.obs.websocket.client.reconnect.{ReconnectDecision, ReconnectPolicy}
import com.worxbend.obs.websocket.client.{Next, ObsConfig, ObsError}
import munit.FunSuite
import scala.concurrent.duration.*

class ReconnectingObsClientSuite extends FunSuite:
  private val policy = ReconnectPolicy
    .create(maxRetries = 3, initialDelay = 1.millis, maxDelay = 4.millis, jitterFraction = 0.0)
    .toOption
    .get

  test("opt-in public entrypoint owns a real sttp connection with defaults"):
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
      ReconnectingObsClient.run(config = ObsConfig(uri = uri), policy = policy): (generation, _) =>
        ReconnectDecision.Complete(value = generation.value)
    assertEquals(result, Right(1L))

  test("documented event-recovery example compiles and validates config before connecting"):
    def nextSceneChange(config: ObsConfig): Either[ObsError, Event] =
      ReconnectPolicy
        .create()
        .flatMap: policy =>
          ReconnectingObsClient.run(config = config, policy = policy): (_, session) =>
            session.withEvents(eventTypes = Set("CurrentProgramSceneChanged"))(use = _.next()) match
              case Right(Next.Item(event))   => ReconnectDecision.Complete(value = event)
              case Right(Next.Failed(error)) =>
                ReconnectDecision.Retry(cause = error, desiredSubscriptions = config.eventSubscriptions)
              case Right(Next.Ended) =>
                ReconnectDecision.Retry(cause = ObsError.Closed, desiredSubscriptions = config.eventSubscriptions)
              case Left(error) =>
                ReconnectDecision.Retry(cause = error, desiredSubscriptions = config.eventSubscriptions)
    assert(nextSceneChange(config = ObsConfig(uri = "http://invalid")).isLeft)
