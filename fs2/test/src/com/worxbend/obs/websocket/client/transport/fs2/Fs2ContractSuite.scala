package com.worxbend.obs.websocket.client.transport.fs2

import com.worxbend.obs.websocket.client.reconnect.{
  ConnectionGeneration,
  ReconnectDecision,
  ReconnectNotice,
  ReconnectPolicy,
}
import com.worxbend.obs.websocket.client.transport.HandshakeHeaders
import com.worxbend.obs.websocket.client.transport.sttp.BackendContractSuite
import com.worxbend.obs.websocket.client.{ObsConfig, ObsError, ObsSession}
import scala.concurrent.duration.*

class Fs2ContractSuite extends BackendContractSuite:
  override protected def backendName: String = "fs2"
  override protected type Options = Fs2Options
  override protected def defaultOptions: Options      = Fs2Options()
  override protected def invalidWriteOptions: Options = Fs2Options(writeTimeout = Duration.Zero)
  override protected def headerOptions(entries: Vector[(String, String)]): Options =
    Fs2Options(headers = HandshakeHeaders.create(entries = entries).toOption.get)
  override protected def connect[A](config: ObsConfig, options: Options)(
    use: ObsSession => A
  ): Either[ObsError, A] =
    Fs2ObsClient.connect(config = config, options = options)(use = use)
  override protected def connectWithDefaults[A](config: ObsConfig)(use: ObsSession => A): Either[ObsError, A] =
    Fs2ObsClient.connect(config = config)(use = use)
  override protected def reconnect[A](config: ObsConfig, policy: ReconnectPolicy, onNotice: ReconnectNotice => Unit)(
    use: (ConnectionGeneration, ObsSession) => ReconnectDecision[A]
  ): Either[ObsError, A] =
    Fs2ReconnectingObsClient.run(config = config, policy = policy, onNotice = onNotice)(use = use)
  override protected def reconnectWithDefaults[A](config: ObsConfig, policy: ReconnectPolicy)(
    use: (ConnectionGeneration, ObsSession) => ReconnectDecision[A]
  ): Either[ObsError, A] =
    Fs2ReconnectingObsClient.run(config = config, policy = policy)(use = use)
  override protected def expectedCleanCloseCode: Option[Int] = Some(1005)
