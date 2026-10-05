package com.worxbend.obs.websocket.client.transport.pekko

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

class PekkoContractSuite extends BackendContractSuite:
  override protected def backendName: String = "pekko"
  override protected type Options = PekkoOptions
  override protected def defaultOptions: Options      = PekkoOptions()
  override protected def invalidWriteOptions: Options = PekkoOptions(writeTimeout = Duration.Zero)
  override protected def headerOptions(entries: Vector[(String, String)]): Options =
    PekkoOptions(headers = HandshakeHeaders.create(entries = entries).toOption.get)
  override protected def connect[A](config: ObsConfig, options: Options)(
    use: ObsSession => A
  ): Either[ObsError, A] =
    PekkoObsClient.connect(config = config, options = options)(use = use)
  override protected def connectWithDefaults[A](config: ObsConfig)(use: ObsSession => A): Either[ObsError, A] =
    PekkoObsClient.connect(config = config)(use = use)

  /** The owned system's pool shutdown can end teardown in an RST instead of a FIN. */
  override protected def tolerateResetTeardown: Boolean = true

  override protected def reconnect[A](config: ObsConfig, policy: ReconnectPolicy, onNotice: ReconnectNotice => Unit)(
    use: (ConnectionGeneration, ObsSession) => ReconnectDecision[A]
  ): Either[ObsError, A] =
    PekkoReconnectingObsClient.run(config = config, policy = policy, onNotice = onNotice)(use = use)
  override protected def reconnectWithDefaults[A](config: ObsConfig, policy: ReconnectPolicy)(
    use: (ConnectionGeneration, ObsSession) => ReconnectDecision[A]
  ): Either[ObsError, A] =
    PekkoReconnectingObsClient.run(config = config, policy = policy)(use = use)
  override protected def expectedCleanCloseCode: Option[Int] =
    Some(1000) // pekko-http completes the flow; sttp synthesizes 1000
