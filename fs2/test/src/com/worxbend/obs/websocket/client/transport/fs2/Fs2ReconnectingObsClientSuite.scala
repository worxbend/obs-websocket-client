package com.worxbend.obs.websocket.client.transport.fs2

import com.worxbend.obs.websocket.client.protocol.Protocol
import com.worxbend.obs.websocket.client.reconnect.{Reconnect, ReconnectDecision, ReconnectPolicy, ReconnectTiming}
import com.worxbend.obs.websocket.client.transport.{HandshakeHeaders, JdkClientOptions}
import com.worxbend.obs.websocket.client.transport.sttp.LocalWebSocketPeer
import com.worxbend.obs.websocket.client.{EventSubscriptions, ObsConfig, ObsError, ObsSession}
import java.net.{Proxy, ProxySelector, SocketAddress, URI}
import java.io.IOException
import javax.net.ssl.SSLContext
import munit.FunSuite
import scala.concurrent.duration.*

class Fs2ReconnectingObsClientSuite extends FunSuite:
  private val policy =
    ReconnectPolicy.create(maxRetries = 3, initialDelay = 1.millis, jitterFraction = 0.0).toOption.get
  private val immediate = ReconnectTiming(() => 0.5, _ => ())

  test("owned-client settings and backend options are forwarded to every attempt and fresh generation"):
    val proxy = new ProxySelector:
      def select(uri:        URI): java.util.List[Proxy]                            = java.util.List.of(Proxy.NO_PROXY)
      def connectFailed(uri: URI, address: SocketAddress, error: IOException): Unit = ()
    val tls           = SSLContext.getDefault
    val clientOptions = JdkClientOptions(proxy = Some(proxy), sslContext = Some(tls))
    val options       = Fs2Options(
      writeTimeout = 2.seconds,
      headers      = HandshakeHeaders.create(entries = Vector("X-Reconnect" -> "retained")).toOption.get,
    )
    val transient = ObsError.Transport(message = "opening failed")
    var calls     = Vector.empty[(ObsConfig, Fs2Options, JdkClientOptions)]
    val factory   = new Fs2ReconnectingObsClient.ClientConnector:
      def connect[A](config: ObsConfig, options: Fs2Options, clientOptions: JdkClientOptions)(
        use: ObsSession => A
      ): Either[ObsError, A] =
        calls = calls :+ ((config, options, clientOptions))
        if calls.size == 1 then Left(transient)
        else Fs2ObsClient.connect(config = config, options = options, clientOptions = clientOptions)(use = use)

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

    val desired = EventSubscriptions.fromLong(value = 65536).toOption.get
    val result  = LocalWebSocketPeer.runAll(scripts = List(serve, serve)): uri =>
      Reconnect.run(
        connector =
          Fs2ReconnectingObsClient.connector(options = options, clientOptions = clientOptions, factory = factory),
        config   = ObsConfig(uri = uri),
        policy   = policy,
        timing   = immediate,
        onNotice = _ => (),
      ): (generation, _) =>
        if generation.value == 2 then ReconnectDecision.Retry(cause = transient, desiredSubscriptions = desired)
        else ReconnectDecision.Complete(value                       = generation.value)
    assertEquals(result, Right(3L))
    assertEquals(calls.size, 3)
    calls.foreach: (_, forwardedOptions, forwardedClientOptions) =>
      assert(forwardedOptions eq options)
      assert(forwardedClientOptions eq clientOptions)
      assert(forwardedClientOptions.proxy.get eq proxy)
      assert(forwardedClientOptions.sslContext.get eq tls)
    assertEquals(calls.last._1.eventSubscriptions, desired)

  test("owned-client default notice handles one retry and preserves the final upgrade error"):
    val oneRetry =
      ReconnectPolicy.create(maxRetries = 1, initialDelay = 1.millis, jitterFraction = 0.0).toOption.get
    val attempts                              = new java.util.concurrent.atomic.AtomicInteger()
    def reject(socket: java.net.Socket): Unit =
      val headers = LocalWebSocketPeer.readHeaders(socket)
      assert(headers.toLowerCase.contains("upgrade: websocket"))
      val _ = attempts.incrementAndGet()
      socket.getOutputStream.write(
        "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
          .getBytes(java.nio.charset.StandardCharsets.UTF_8)
      )
      socket.getOutputStream.flush()
    val started = System.nanoTime()
    val result  = LocalWebSocketPeer.runAll(scripts = List(reject, reject)): uri =>
      Fs2ReconnectingObsClient.run(
        config        = ObsConfig(uri = uri, connectionTimeout = 1.second),
        policy        = oneRetry,
        clientOptions = JdkClientOptions(),
      ): (_, _) =>
        fail("Rejected upgrades must not reach the session callback")
    assertEquals(result, Left(ObsError.Transport(message = "WebSocket upgrade rejected")))
    assertEquals(attempts.get(), 2)
    assert(System.nanoTime() - started < 6.seconds.toNanos)

  test("original and owned-client overloads preserve config and backend option validation"):
    val invalid       = ObsConfig(uri = "http://invalid")
    val clientOptions = JdkClientOptions()
    assert(Fs2ReconnectingObsClient.run(config = invalid, policy = policy): (_, _) =>
      ReconnectDecision.Complete(value = ())
    .isLeft)
    assert(Fs2ReconnectingObsClient.run(config = invalid, policy = policy, clientOptions = clientOptions): (_, _) =>
      ReconnectDecision.Complete(value = ())
    .isLeft)
    val invalidOptions = Fs2Options(writeTimeout = Duration.Zero)
    val result         = Fs2ReconnectingObsClient.run(
      config        = ObsConfig(),
      policy        = policy,
      timing        = immediate,
      onNotice      = _ => fail("Invalid backend options must not connect"),
      options       = invalidOptions,
      clientOptions = clientOptions,
    ): (_, _) =>
      ReconnectDecision.Complete(value = ())
    assertEquals(result.swap.toOption, invalidOptions.validate.swap.toOption)
