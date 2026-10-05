package com.worxbend.obs.websocket.client.transport.okhttp

import com.worxbend.obs.websocket.client.reconnect.{
  ConnectionGeneration,
  ReconnectDecision,
  ReconnectNotice,
  ReconnectPolicy,
}
import com.worxbend.obs.websocket.client.transport.HandshakeHeaders
import com.worxbend.obs.websocket.client.transport.sttp.{BackendContractSuite, SttpOptions}
import com.worxbend.obs.websocket.client.{ObsConfig, ObsError, ObsSession}
import scala.concurrent.duration.*

class OkHttpContractSuite extends BackendContractSuite:
  override protected def backendName: String = "okhttp"
  override protected type Options = SttpOptions
  override protected def defaultOptions: Options      = SttpOptions()
  override protected def invalidWriteOptions: Options = SttpOptions(writeTimeout = Duration.Zero)
  override protected def headerOptions(entries: Vector[(String, String)]): Options =
    SttpOptions(headers = HandshakeHeaders.create(entries = entries).toOption.get)
  override protected def connect[A](config: ObsConfig, options: Options)(
    use: ObsSession => A
  ): Either[ObsError, A] =
    OkHttpObsClient.connect(config = config, options = options)(use = use)
  override protected def connectWithDefaults[A](config: ObsConfig)(use: ObsSession => A): Either[ObsError, A] =
    OkHttpObsClient.connect(config = config)(use = use)

  /** OkHttp reports a failed handshake as a generic `ProtocolException`, not the JDK handshake exception. */
  override protected def upgradeRejectionMessage: String = "WebSocket connection failed"

  override protected def reconnect[A](config: ObsConfig, policy: ReconnectPolicy, onNotice: ReconnectNotice => Unit)(
    use: (ConnectionGeneration, ObsSession) => ReconnectDecision[A]
  ): Either[ObsError, A] =
    OkHttpReconnectingObsClient.run(config = config, policy = policy, onNotice = onNotice)(use = use)
  override protected def reconnectWithDefaults[A](config: ObsConfig, policy: ReconnectPolicy)(
    use: (ConnectionGeneration, ObsSession) => ReconnectDecision[A]
  ): Either[ObsError, A] =
    OkHttpReconnectingObsClient.run(config = config, policy = policy)(use = use)
  override protected def expectedCleanCloseCode: Option[Int] = Some(1005)
