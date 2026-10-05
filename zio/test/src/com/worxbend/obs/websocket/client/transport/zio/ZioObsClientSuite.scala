package com.worxbend.obs.websocket.client.transport.zio

import com.worxbend.obs.websocket.client.reconnect.{ReconnectDecision, ReconnectPolicy}
import com.worxbend.obs.websocket.client.{ObsConfig, ObsError}
import munit.FunSuite
import scala.concurrent.duration.*
import sttp.client4.httpclient.zio.HttpClientZioBackend

/** ZIO-specific client cases; the cross-backend wire contract lives in [[ZioContractSuite]]. */
class ZioObsClientSuite extends FunSuite:
  test("injected backend entrypoint rejects malformed URI as configuration"):
    val result = ZioObsClient.withBackend(
      backend = HttpClientZioBackend.stub,
      config  = ObsConfig(uri = "ws://localhost:invalid"),
      () => fail("No connection acquired"),
    )(_ => ())
    assertEquals(
      result,
      Left(
        ObsError.InvalidConfiguration(message = "Expected ws/wss URI with host, without credentials, query or fragment")
      ),
    )

  test("injected backend entrypoint validates transport options"):
    assertEquals(
      ZioObsClient.withBackend(
        backend = HttpClientZioBackend.stub,
        config  = ObsConfig(),
        () => fail("No connection acquired"),
        options = ZioOptions(writeTimeout = Duration.Zero),
      )(_ => ()),
      Left(ObsError.InvalidConfiguration(message = "Write deadline must be positive")),
    )

  test("residual sttp URI parse failures are configuration errors"):
    assertEquals(
      ZioObsClient.websocketUri(config = ObsConfig(uri = "ws://localhost:invalid")),
      Left(ObsError.InvalidConfiguration(message = "Invalid WebSocket URI")),
    )
    assert(ZioObsClient.websocketUri(config = ObsConfig()).isRight)

  test("client options apply proxy and TLS configuration to the owned JDK client"):
    val clientOptions = com.worxbend.obs.websocket.client.transport.JdkClientOptions(
      proxy      = Some(java.net.ProxySelector.of(new java.net.InetSocketAddress("127.0.0.1", 1))),
      sslContext = Some(javax.net.ssl.SSLContext.getDefault),
    )
    val result =
      ZioObsClient.connect(config = ObsConfig(uri = "ws://127.0.0.1:1"), clientOptions = clientOptions)(_ => ())
    assert(result.isLeft)

  test("reconnect entrypoint validates transport options before connecting"):
    val policy = ReconnectPolicy.create(maxRetries = 0).toOption.get
    assertEquals(
      ZioReconnectingObsClient.run(
        config  = ObsConfig(),
        policy  = policy,
        options = ZioOptions(writeTimeout = Duration.Zero),
      )((_, _) => ReconnectDecision.Complete(value = ())),
      Left(ObsError.InvalidConfiguration(message = "Write deadline must be positive")),
    )
