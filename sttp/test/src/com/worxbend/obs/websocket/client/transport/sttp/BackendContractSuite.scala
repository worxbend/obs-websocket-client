package com.worxbend.obs.websocket.client.transport.sttp

import com.worxbend.obs.websocket.client.protocol.Protocol
import com.worxbend.obs.websocket.client.reconnect.{
  ConnectionGeneration,
  ReconnectDecision,
  ReconnectNotice,
  ReconnectPolicy,
}
import com.worxbend.obs.websocket.client.{ObsConfig, ObsError, ObsSession}
import munit.FunSuite
import scala.concurrent.duration.*

/** Cross-backend wire contract: every published backend adapter must pass these cases against a scripted local peer.
  * Concrete subclasses in each adapter's test module bind the backend's options type, entrypoints, and documented
  * divergences (upgrade-rejection wording, close-code surfacing, teardown style). Coverage attribution follows the
  * production module because each adapter's own test JVM executes the subclass.
  */
abstract class BackendContractSuite extends FunSuite:
  protected def backendName: String
  protected type Options
  protected def defaultOptions: Options
  protected def invalidWriteOptions: Options
  protected def headerOptions(entries: Vector[(String, String)]): Options
  protected def connect[A](config: ObsConfig, options: Options)(use: ObsSession => A): Either[ObsError, A]

  /** The bare entrypoint form, exercising each module's default argument values. */
  protected def connectWithDefaults[A](config: ObsConfig)(use: ObsSession => A): Either[ObsError, A]
  protected def reconnect[A](config: ObsConfig, policy: ReconnectPolicy, onNotice: ReconnectNotice => Unit)(
    use: (ConnectionGeneration, ObsSession) => ReconnectDecision[A]
  ): Either[ObsError, A]

  /** The bare reconnect form, exercising each wrapper's default timing and notice arguments. */
  protected def reconnectWithDefaults[A](config: ObsConfig, policy: ReconnectPolicy)(
    use: (ConnectionGeneration, ObsSession) => ReconnectDecision[A]
  ): Either[ObsError, A]

  /** okkhttp maps handshake rejections differently; every other adapter shares the default. */
  protected def upgradeRejectionMessage: String = "WebSocket upgrade rejected"

  /** What an empty-payload peer close surfaces as: Pekko synthesizes 1000; JDK-backed adapters surface the
    * protocol-level no-status code.
    */
  protected def expectedCleanCloseCode: Option[Int]

  /** Pekko's aggressive pool shutdown can end teardown in an RST instead of a FIN. */
  protected def tolerateResetTeardown: Boolean = false

  private val hello      = """{"op":0,"d":{"obsWebSocketVersion":"5.6.3","rpcVersion":1}}"""
  private val identified = """{"op":2,"d":{"negotiatedRpcVersion":1}}"""

  private def assertTcpReleased(socket: java.net.Socket): Unit =
    try assertEquals(socket.getInputStream.read(), -1)
    catch
      case _: java.net.SocketException =>
        assert(tolerateResetTeardown, s"$backendName teardown must end in FIN, not RST")

  /** JDK-backed adapters can legitimately emit two pongs for one ping (the JDK client answers at the protocol
    * layer and the transport loop answers through its frame mapping), so control frames are skipped here.
    */
  private def respondIdentify(socket: java.net.Socket): Unit =
    var frame = LocalWebSocketPeer.receive(socket = socket)
    while frame._1 == 10 do frame = LocalWebSocketPeer.receive(socket = socket)
    assertEquals(Protocol.decode(text = frame._2).toOption.get.op, 1)
    LocalWebSocketPeer.send(socket = socket, text = identified)

  private def respondDiscovery(socket: java.net.Socket, availableRequests: Vector[String] = Vector.empty): Unit =
    val request  = Protocol.decode(text = LocalWebSocketPeer.receive(socket = socket)._2).toOption.get
    val id       = request.data.string(name = "requestId").toOption.get
    val requests = availableRequests.map(name => s""""$name"""").mkString(",")
    LocalWebSocketPeer.send(
      socket = socket,
      text   =
        s"""{"op":7,"d":{"requestType":"GetVersion","requestId":"$id","requestStatus":{"result":true,"code":100},"responseData":{"availableRequests":[$requests]}}}""",
    )

  test(s"$backendName: real WebSocket handshake, fragmented Hello, version discovery, clean scoped close"):
    def serve(socket: java.net.Socket): Unit =
      LocalWebSocketPeer.upgrade(socket = socket)
      LocalWebSocketPeer.send(socket    = socket, text              = hello.take(10), finalFragment = false)
      LocalWebSocketPeer.send(socket    = socket, text              = hello.drop(10), opcode        = 0)
      respondIdentify(socket            = socket)
      respondDiscovery(socket           = socket, availableRequests = Vector("GetVersion"))
      assertEquals(LocalWebSocketPeer.receive(socket = socket)._1, 8)
      assertTcpReleased(socket = socket)
    val result = LocalWebSocketPeer.run(server = serve): uri =>
      connect(config = ObsConfig(uri = uri), options = defaultOptions)(use = _.metadata.availableRequests)
    assertEquals(result, Right(Set("GetVersion")))

  test(s"$backendName: a peer ping is answered with a pong"):
    def serve(socket: java.net.Socket): Unit =
      LocalWebSocketPeer.upgrade(socket = socket)
      LocalWebSocketPeer.send(socket    = socket, text = "", opcode = 9)
      assertEquals(LocalWebSocketPeer.receive(socket = socket)._1, 10, "The ping must be answered with a pong")
      LocalWebSocketPeer.send(socket = socket, text = hello)
      respondIdentify(socket         = socket)
      respondDiscovery(socket        = socket)
      assertEquals(LocalWebSocketPeer.receive(socket = socket)._1, 8)
    val result = LocalWebSocketPeer.run(server = serve): uri =>
      connect(config = ObsConfig(uri = uri), options = defaultOptions)(_ => ())
    assertEquals(result, Right(()))

  test(s"$backendName: a peer error close preserves the status code it sent"):
    def serve(socket: java.net.Socket): Unit =
      LocalWebSocketPeer.upgrade(socket = socket)
      LocalWebSocketPeer.send(socket    = socket, text = hello)
      respondIdentify(socket            = socket)
      assertEquals(Protocol.decode(text = LocalWebSocketPeer.receive(socket = socket)._2).toOption.get.op, 6)
      // Code 3328 = 0x0D00; LocalWebSocketPeer payloads are UTF-8, so both bytes must stay ASCII.
      LocalWebSocketPeer.send(socket = socket, text = "\r\u0000", opcode = 8)
    val result = LocalWebSocketPeer.run(server = serve): uri =>
      connect(config = ObsConfig(uri = uri), options = defaultOptions)(use = _.rawRequest(requestType = "GetVersion"))
    assertEquals(result, Left(ObsError.Transport(message = "WebSocket closed", closeCode = Some(3328))))

  test(s"$backendName: HTTP upgrade rejection is a typed redacted error"):
    def serve(socket: java.net.Socket): Unit =
      val _ = LocalWebSocketPeer.readHeaders(socket = socket)
      socket.getOutputStream.write(
        "HTTP/1.1 403 Forbidden\r\nContent-Length: 6\r\nConnection: close\r\n\r\nsecret".getBytes(
          java.nio.charset.StandardCharsets.UTF_8
        )
      )
      socket.getOutputStream.flush()
    val result = LocalWebSocketPeer.run(server = serve): uri =>
      connect(config = ObsConfig(uri = uri), options = defaultOptions)(_ => ())
    assertEquals(result, Left(ObsError.Transport(message = upgradeRejectionMessage)))

  test(s"$backendName: non-handshake connection errors are typed and redacted"):
    val result = connectWithDefaults(config = ObsConfig(uri = "ws://127.0.0.1:1"))(_ => ())
    assertEquals(result, Left(ObsError.Transport(message = "WebSocket connection failed")))

  test(s"$backendName: connection deadline bounds acquisition of a silent upgrade and releases TCP"):
    def serve(socket: java.net.Socket): Unit =
      val _ = LocalWebSocketPeer.readHeaders(socket = socket)
      assertTcpReleased(socket = socket)
    val result = LocalWebSocketPeer.run(server = serve): uri =>
      connect(config = ObsConfig(uri = uri, connectionTimeout = 100.millis), options = defaultOptions)(_ => ())
    assertEquals(result, Left(ObsError.Timeout(operation = "connection")))

  test(s"$backendName: authentication required by the server without a password fails the handshake"):
    def serve(socket: java.net.Socket): Unit =
      LocalWebSocketPeer.upgrade(socket = socket)
      LocalWebSocketPeer.send(
        socket = socket,
        text   =
          """{"op":0,"d":{"obsWebSocketVersion":"5.7.0","rpcVersion":1,"authentication":{"challenge":"c","salt":"s"}}}""",
      )
      assertEquals(LocalWebSocketPeer.receive(socket = socket)._1, 8)
    val result = LocalWebSocketPeer.run(server = serve): uri =>
      connect(config = ObsConfig(uri = uri), options = defaultOptions)(_ => ())
    assert(result.left.exists(_.isInstanceOf[ObsError.Authentication]))

  test(s"$backendName: invalid configuration is rejected before any backend resources are built"):
    assert(connect(config = ObsConfig(uri = "https://localhost"), options = defaultOptions)(_ => ()).isLeft)

  test(s"$backendName: invalid transport options are rejected before any backend resources are built"):
    assertEquals(
      connect(config = ObsConfig(), options = invalidWriteOptions)(_ => ()),
      Left(ObsError.InvalidConfiguration(message = "Write deadline must be positive")),
    )

  test(s"$backendName: custom upgrade header reaches a real peer"):
    def reject(socket: java.net.Socket): Unit =
      assert(LocalWebSocketPeer.readHeaders(socket = socket).toLowerCase.contains("x-backend: contract\r\n"))
      socket.getOutputStream.write(
        "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
          .getBytes(java.nio.charset.StandardCharsets.US_ASCII)
      )
      socket.getOutputStream.flush()
    val options = headerOptions(entries = Vector("X-Backend" -> "contract"))
    val result  = LocalWebSocketPeer.run(server = reject): uri =>
      connect(config = ObsConfig(uri = uri), options = options)(_ => ())
    assertEquals(result, Left(ObsError.Transport(message = upgradeRejectionMessage)))

  test(s"$backendName: positive submillisecond deadlines construct the real backend without truncation errors"):
    val result = connect(
      config  = ObsConfig(uri = "ws://127.0.0.1:1", connectionTimeout = 1.nanosecond),
      options = defaultOptions,
    )(_ => ())
    assert(result.isLeft)

  test(s"$backendName: opt-in reconnect entrypoint owns a real connection with defaults"):
    def serve(socket: java.net.Socket): Unit =
      LocalWebSocketPeer.upgrade(socket = socket)
      LocalWebSocketPeer.send(socket    = socket, text = hello)
      respondIdentify(socket            = socket)
      respondDiscovery(socket           = socket)
      assertEquals(LocalWebSocketPeer.receive(socket = socket)._1, 8)
    val policy = ReconnectPolicy
      .create(maxRetries = 3, initialDelay = 1.millis, maxDelay = 4.millis, jitterFraction = 0.0)
      .toOption
      .get
    val result = LocalWebSocketPeer.run(server = serve): uri =>
      reconnectWithDefaults(config = ObsConfig(uri = uri), policy = policy): (generation, _) =>
        ReconnectDecision.Complete(value = generation.value)
    assertEquals(result, Right(1L))

  test(s"$backendName: a peer normal close surfaces the protocol-level termination state"):
    def serve(socket: java.net.Socket): Unit =
      LocalWebSocketPeer.upgrade(socket = socket)
      LocalWebSocketPeer.send(socket    = socket, text = hello)
      respondIdentify(socket            = socket)
      assertEquals(Protocol.decode(text = LocalWebSocketPeer.receive(socket = socket)._2).toOption.get.op, 6)
      LocalWebSocketPeer.send(socket = socket, text = "", opcode = 8)
    val result = LocalWebSocketPeer.run(server = serve): uri =>
      connect(config = ObsConfig(uri = uri), options = defaultOptions)(use = _.rawRequest(requestType = "GetVersion"))
    assertEquals(
      result,
      Left(ObsError.Transport(message = "WebSocket closed", closeCode = expectedCleanCloseCode)),
    )

  test(s"$backendName: reconnect after an abrupt drop opens a fresh generation on a new connection"):
    def firstConnection(socket: java.net.Socket): Unit =
      LocalWebSocketPeer.upgrade(socket = socket)
      LocalWebSocketPeer.send(socket    = socket, text = hello)
      respondIdentify(socket            = socket)
      respondDiscovery(socket           = socket)
      // The callback's request lands; the server then drops the connection with a retryable shutdown code
      // (1011: valid per pekko-http's CloseCodes and retryable per ReconnectPolicy).
      assertEquals(Protocol.decode(text = LocalWebSocketPeer.receive(socket = socket)._2).toOption.get.op, 6)
      LocalWebSocketPeer.sendClose(socket = socket, statusCode = 1011)
    def secondConnection(socket: java.net.Socket): Unit =
      LocalWebSocketPeer.upgrade(socket = socket)
      LocalWebSocketPeer.send(socket    = socket, text = hello)
      respondIdentify(socket            = socket)
      respondDiscovery(socket           = socket)
      assertEquals(LocalWebSocketPeer.receive(socket = socket)._1, 8)
    val policy = ReconnectPolicy
      .create(maxRetries = 3, initialDelay = 1.millis, maxDelay = 4.millis, jitterFraction = 0.0)
      .toOption
      .get
    var notices = Vector.empty[ReconnectNotice]
    val result  = LocalWebSocketPeer.runAll(scripts = List(firstConnection, secondConnection)): uri =>
      val config = ObsConfig(uri = uri)
      reconnect(config = config, policy = policy, onNotice = notice => notices = notices :+ notice):
        (generation, session) =>
          if generation.value == 1 then
            ReconnectDecision.Retry(
              cause                = session.rawRequest(requestType = "GetVersion").swap.toOption.get,
              desiredSubscriptions = config.eventSubscriptions,
            )
          else ReconnectDecision.Complete(value = generation.value)
    assertEquals(result, Right(2L))
    assert(notices.exists(_.isInstanceOf[ReconnectNotice.EventGap]), s"missing EventGap in $notices")
    assert(notices.exists(_.isInstanceOf[ReconnectNotice.RetryScheduled]), s"missing RetryScheduled in $notices")
    assert(
      notices.exists:
        case ReconnectNotice.Reconnected(generation, previous) => generation.value == 2 && previous.value == 1
        case _                                                 => false
      ,
      s"missing Reconnected(2 <- 1) in $notices",
    )
