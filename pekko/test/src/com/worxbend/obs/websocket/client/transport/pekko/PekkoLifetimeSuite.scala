package com.worxbend.obs.websocket.client.transport.pekko

import com.worxbend.obs.websocket.client.{ConnectionState, ObsConfig, ObsError}
import com.worxbend.obs.websocket.client.protocol.Protocol
import com.worxbend.obs.websocket.client.transport.sttp.LocalWebSocketPeer
import java.net.{Socket, SocketException, SocketTimeoutException}
import munit.FunSuite
import org.apache.pekko.actor.ActorSystem
import ox.{sleep, timeout}
import scala.concurrent.Promise
import scala.concurrent.duration.*

/** Real RFC6455/TCP peer assertions made before the session callback exits: teardown must not hide broken abort. */
class PekkoLifetimeSuite extends FunSuite:
  test("a quiet native session outlives acquisition timeout with no read-idle policy"):
    val quiet  = Promise[Unit]()
    val result = LocalWebSocketPeer.run(server = socket =>
      identify(socket)
      socket.setSoTimeout(1400)
      val _ = intercept[SocketTimeoutException](socket.getInputStream.read())
      val _ = quiet.success(())
      socket.setSoTimeout(3000)
      completeClose(socket)): uri =>
      PekkoObsClient.connect(config = ObsConfig(uri = uri, connectionTimeout = 1.second)): session =>
        PekkoRunner.await(quiet.future)
        assertEquals(session.state, Right(ConnectionState.Ready))
    assertEquals(result, Right(()))

  test("native read timeout physically releases TCP while the callback remains alive"):
    val entered  = Promise[Unit]()
    val released = Promise[Unit]()
    val result   = LocalWebSocketPeer.run(server = socket =>
      identify(socket)
      PekkoRunner.await(entered.future)
      awaitRelease(socket)
      val _ = released.success(())): uri =>
      PekkoObsClient.connect(
        config  = ObsConfig(uri = uri, connectionTimeout = 4.seconds),
        options = PekkoOptions(readIdleTimeout = Some(300.millis)),
      ): session =>
        val _ = entered.success(())
        PekkoRunner.await(released.future)
        awaitFailed(() => session.state)
    assertEquals(result, Right(()))

  test("native oversized delivery physically releases TCP while the callback remains alive"):
    val entered  = Promise[Unit]()
    val released = Promise[Unit]()
    val result   = LocalWebSocketPeer.run(server = socket =>
      identify(socket)
      PekkoRunner.await(entered.future)
      LocalWebSocketPeer.send(socket, "x" * 1025)
      awaitRelease(socket)
      val _ = released.success(())): uri =>
      PekkoObsClient.connect(config = ObsConfig(uri = uri, maxMessageBytes = 1024)): session =>
        val _ = entered.success(())
        PekkoRunner.await(released.future)
        awaitFailed(() => session.state)
    assertEquals(result, Right(()))

  test("a stalled native upgrade is bounded and aborted without terminating a caller-owned system"):
    val system = ActorSystem("pekko-native-acquisition")
    try
      val result = LocalWebSocketPeer.run(server = socket =>
        val _ = LocalWebSocketPeer.readHeaders(socket)
        awaitRelease(socket)): uri =>
        val started = System.nanoTime()
        val outcome = PekkoObsClient.withNativeSystem(
          system  = system,
          config  = ObsConfig(uri = uri, connectionTimeout = 500.millis),
          options = PekkoOptions(),
        )(_ => fail("Upgrade did not complete"))
        assert(System.nanoTime() - started < 3.seconds.toNanos)
        outcome
      assertEquals(result, Left(ObsError.Timeout(operation = "connection")))
      assert(!system.whenTerminated.isCompleted)
      // A new native connection on the SAME system still upgrades and discovers successfully.
      val next = LocalWebSocketPeer.run(server = socket =>
        identify(socket)
        completeClose(socket)): uri =>
        PekkoObsClient.withNativeSystem(system, ObsConfig(uri = uri), PekkoOptions())(_ => ())
      assertEquals(next, Right(()))
      assert(!system.whenTerminated.isCompleted)
    finally PekkoObsClient.shutdownSystem(system)

  test("interruption aborts native acquisition before its longer connection deadline"):
    val system = ActorSystem("pekko-native-interruption")
    try
      val result = LocalWebSocketPeer.run(server = socket =>
        val _ = LocalWebSocketPeer.readHeaders(socket)
        awaitRelease(socket)): uri =>
        ox.timeoutOption(1.second):
          PekkoObsClient.withNativeSystem(system, ObsConfig(uri = uri, connectionTimeout = 5.seconds), PekkoOptions())(
            _ => fail("Upgrade did not complete")
          )
      assertEquals(result, None)
      assert(!system.whenTerminated.isCompleted)
    finally PekkoObsClient.shutdownSystem(system)

  test("native graceful Close is delivered but bounded when the peer never replies"):
    val result = LocalWebSocketPeer.run(server = socket =>
      identify(socket)
      assertEquals(LocalWebSocketPeer.receive(socket)._1, 8)
      awaitRelease(socket)): uri =>
      PekkoObsClient.connect(config = ObsConfig(uri = uri, shutdownTimeout = 200.millis))(_ => ())
    assertEquals(result, Right(()))

  test("aborting one native connection leaves another live connection on the same system usable"):
    val system = ActorSystem("pekko-native-isolation")
    try
      val result = LocalWebSocketPeer.run(server = socket =>
        identify(socket)
        val request = Protocol.decode(LocalWebSocketPeer.receive(socket)._2).toOption.get
        respondVersion(socket, request.data.string("requestId").toOption.get)
        completeClose(socket)): uri =>
        PekkoObsClient.withNativeSystem(system, ObsConfig(uri = uri), PekkoOptions()): healthy =>
          val stalled = LocalWebSocketPeer.run(server = socket =>
            val _ = LocalWebSocketPeer.readHeaders(socket)
            awaitRelease(socket)): stalledUri =>
            PekkoObsClient.withNativeSystem(
              system,
              ObsConfig(uri = stalledUri, connectionTimeout = 500.millis),
              PekkoOptions(),
            )(_ => fail("Upgrade did not complete"))
          assertEquals(stalled, Left(ObsError.Timeout(operation = "connection")))
          assertEquals(healthy.state, Right(ConnectionState.Ready))
          assert(healthy.rawRequest(requestType = "GetVersion").isRight)
      assertEquals(result, Right(()))
    finally PekkoObsClient.shutdownSystem(system)

  test("injected native factory reaches the callback and closes without owning the shared system"):
    val system    = ActorSystem("pekko-injected-native-success")
    val callbacks = new java.util.concurrent.atomic.AtomicInteger()
    val aborts    = new java.util.concurrent.atomic.AtomicInteger()
    try
      val result = LocalWebSocketPeer.run(server = socket =>
        identify(socket)
        completeClose(socket)): uri =>
        val connection = PekkoConnectionFactory.create(system, 1.second)
        // The injected factory independently bounds acquisition, including an upgrade that never answers.
        val deadline = system.scheduler.scheduleOnce(2.seconds)(connection.abort())(using system.dispatcher)
        try
          PekkoObsClient.withBackend(
            backend         = connection.backend,
            config          = ObsConfig(uri = uri, connectionTimeout = 3.seconds),
            abortConnection = () =>
              // withBackend has no native awaitClosed hook. Allow the enqueued Close to reach the wire before
              // force-aborting, but never let an unresponsive peer keep this abort pending indefinitely.
              val _ = ox.timeoutOption(500.millis)(connection.awaitClosed())
              val _ = aborts.incrementAndGet()
              connection.abort(),
          ): session =>
            val _ = deadline.cancel()
            val _ = callbacks.incrementAndGet()
            assertEquals(session.state, Right(ConnectionState.Ready))
            "callback-result"
        finally
          val _ = deadline.cancel()
          connection.abort()
          connection.abort() // Per-connection destruction remains idempotent.
      assertEquals(result, Right("callback-result"))
      assertEquals(callbacks.get(), 1)
      assert(aborts.get() >= 1)
      assert(!system.whenTerminated.isCompleted)
      val next = LocalWebSocketPeer.run(server = socket =>
        identify(socket)
        completeClose(socket)): uri =>
        PekkoObsClient.withNativeSystem(system, ObsConfig(uri = uri), PekkoOptions())(_ => "still-owned")
      assertEquals(next, Right("still-owned"))
      assert(!system.whenTerminated.isCompleted)
    finally PekkoObsClient.shutdownSystem(system)

  test("native factory delegates ordinary HTTP requests to a real local HTTP peer"):
    val system     = ActorSystem("pekko-native-http-delegation")
    val connection = PekkoConnectionFactory.create(system, 1.second)
    try
      val result = LocalWebSocketPeer.run(server = socket =>
        val headers = LocalWebSocketPeer.readHeaders(socket)
        assert(headers.startsWith("GET /delegated?check=1 HTTP/1.1\r\n"))
        socket.getOutputStream.write(
          "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 9\r\nConnection: close\r\n\r\ndelegated"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8)
        )
        socket.getOutputStream.flush()): uri =>
        val httpUri = sttp.model.Uri.parse(uri.replace("ws://", "http://") + "/delegated?check=1").toOption.get
        PekkoRunner.await(sttp.client4.basicRequest.get(httpUri).readTimeout(1.second).send(connection.backend))
      assertEquals(result.code, sttp.model.StatusCode.Ok)
      assertEquals(result.body, Right("delegated"))
      assert(!system.whenTerminated.isCompleted)
    finally
      connection.abort()
      PekkoObsClient.shutdownSystem(system)

  private def identify(socket: Socket): Unit =
    LocalWebSocketPeer.upgrade(socket)
    LocalWebSocketPeer.send(socket, """{"op":0,"d":{"obsWebSocketVersion":"5.6.3","rpcVersion":1}}""")
    assertEquals(LocalWebSocketPeer.receive(socket)._1, 1)
    LocalWebSocketPeer.send(socket, """{"op":2,"d":{"negotiatedRpcVersion":1}}""")
    val request = Protocol.decode(LocalWebSocketPeer.receive(socket)._2).toOption.get
    respondVersion(socket, request.data.string("requestId").toOption.get)

  private def respondVersion(socket: Socket, id: String): Unit =
    LocalWebSocketPeer.send(
      socket,
      s"""{"op":7,"d":{"requestType":"GetVersion","requestId":"$id","requestStatus":{"result":true,"code":100},"responseData":{"availableRequests":[]}}}""",
    )

  private def completeClose(socket: Socket): Unit =
    assertEquals(LocalWebSocketPeer.receive(socket)._1, 8)
    LocalWebSocketPeer.sendClose(socket, 1000)
    socket.shutdownOutput()
    awaitRelease(socket)

  /** Drain until actual FIN/RST. Receiving a Close frame alone is NOT proof of physical socket release. */
  private def awaitRelease(socket: Socket): Unit =
    socket.setSoTimeout(2000)
    try
      while socket.getInputStream.read() != -1 do ()
    catch case _: SocketException => ()

  private def awaitFailed(state: () => Either[ObsError, ConnectionState]): Unit = timeout(2.seconds):
    while state() != Right(ConnectionState.Failed) do sleep(5.millis)
