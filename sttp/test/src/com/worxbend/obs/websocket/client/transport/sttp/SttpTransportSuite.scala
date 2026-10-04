package com.worxbend.obs.websocket.client.transport.sttp

import com.worxbend.obs.websocket.client.{ObsConfig, ObsError}
import _root_.sttp.client4.ws.SyncWebSocket
import _root_.sttp.model.Headers
import _root_.sttp.monad.{IdentityMonad, MonadError}
import _root_.sttp.shared.Identity
import _root_.sttp.ws.{WebSocket, WebSocketClosed, WebSocketFrame}
import java.io.IOException
import java.util.concurrent.ExecutionException
import munit.FunSuite
import scala.concurrent.duration.*

class SttpTransportSuite extends FunSuite:
  private final class Peer(frames: List[WebSocketFrame], failure: Option[Exception] = None) extends WebSocket[Identity]:
    private var remaining = frames
    var sent: List[WebSocketFrame] = Nil
    override def receive(): WebSocketFrame = failure match
      case Some(error) => throw error
      case None        =>
        val frame = remaining.head
        remaining = remaining.tail
        frame
    override def send(frame: WebSocketFrame, isContinuation: Boolean): Unit =
      failure.foreach(throw _)
      sent = sent :+ frame
    override def isOpen(): Boolean = true
    override val upgradeHeaders: Headers = Headers(Nil)
    override implicit val monad: MonadError[Identity] = IdentityMonad

  private def transport(peer: Peer, limit: Int = 1024): SttpTransport =
    new SttpTransport(new SyncWebSocket(peer), limit, 1.second, () => ())

  test("fragmented text is reassembled across ping and pong control frames"):
    val ping = Array[Byte](1, 2, 3)
    val peer = new Peer(
      List(
        WebSocketFrame.Text("{", false, None),
        WebSocketFrame.Ping(ping),
        WebSocketFrame.pong,
        WebSocketFrame.text("}")
      )
    )
    assertEquals(transport(peer).receive(), Right("{}"))
    assertEquals(peer.sent, List(WebSocketFrame.Pong(ping)))

  test("incoming limit counts UTF-8 bytes across fragments"):
    val peer = new Peer(List(WebSocketFrame.Text("é", false, None), WebSocketFrame.text("é")))
    assertEquals(
      transport(peer, 3).receive(),
      Left(ObsError.MessageTooLarge("Incoming message exceeds configured byte limit"))
    )

  test("exact byte limit is accepted"):
    assertEquals(transport(new Peer(List(WebSocketFrame.text("é"))), 2).receive(), Right("é"))

  test("binary frames fail explicitly"):
    assertEquals(
      transport(new Peer(List(WebSocketFrame.binary(Array[Byte](1))))).receive(),
      Left(ObsError.UnsupportedMessage("Binary messages are unsupported; use OBS JSON encoding"))
    )

  test("close frame preserves status without reflecting peer-controlled text"):
    assertEquals(
      transport(new Peer(List(WebSocketFrame.Close(4009, "secret")))).receive(),
      Left(ObsError.Transport("WebSocket closed", Some(4009)))
    )

  test("backend close exception preserves status"):
    val peer = new Peer(Nil, Some(WebSocketClosed(Some(WebSocketFrame.Close(4011, "secret")))))
    assertEquals(transport(peer).receive(), Left(ObsError.Transport("WebSocket closed", Some(4011))))

  test("abrupt close without a frame is represented"):
    assertEquals(
      transport(new Peer(Nil, Some(WebSocketClosed(None)))).receive(),
      Left(ObsError.Transport("WebSocket closed", None))
    )

  test("I/O exception diagnostics are redacted"):
    assertEquals(
      transport(new Peer(Nil, Some(new IOException("secret")))).receive(),
      Left(ObsError.Transport("WebSocket I/O failed"))
    )

  test("failed future diagnostics are redacted"):
    assertEquals(
      transport(new Peer(Nil, Some(new ExecutionException(new IOException("secret"))))).receive(),
      Left(ObsError.Transport("WebSocket operation failed"))
    )

  test("outgoing byte limit rejects before sending"):
    val peer = new Peer(Nil)
    assertEquals(
      transport(peer, 1).send("é"),
      Left(ObsError.MessageTooLarge("Outgoing message exceeds configured byte limit"))
    )
    assertEquals(peer.sent, Nil)

  test("send forwards text unchanged"):
    val peer = new Peer(Nil)
    assertEquals(transport(peer).send("{}"), Right(()))
    assertEquals(peer.sent, List(WebSocketFrame.text("{}")))

  test("close sends the normal close control frame"):
    val peer = new Peer(Nil)
    transport(peer).close()
    assertEquals(peer.sent, List(WebSocketFrame.close))

  test("socket acquired after the deadline is still released by the enclosing scope"):
    val peer = new Peer(Nil)
    val backend = _root_.sttp.client4.testing.WebSocketSyncBackendStub.whenAnyRequest.thenRespondF:
      (_: _root_.sttp.client4.GenericRequest[?, ?]) =>
        // Models a finite backend upgrade deadline exceeding the caller deadline.
        ox.sleep(50.millis)
        _root_.sttp.client4.testing.ResponseStub.exact(Right(new SyncWebSocket(peer)))
    var aborted = false
    assertEquals(
      SttpObsClient.withBackend(
        backend,
        ObsConfig(connectionTimeout = 5.millis),
        () =>
          assertEquals(peer.sent, List(WebSocketFrame.close))
          aborted = true
      )(_ => ()),
      Left(ObsError.Timeout("connection"))
    )
    assertEquals(peer.sent, List(WebSocketFrame.close))
    assert(aborted)

  test("forced cleanup runs even when close fails"):
    var aborted = false
    val peer = new Peer(Nil, Some(new IOException("close failed")))
    val socket = new SttpTransport(new SyncWebSocket(peer), 1024, 1.second, () => aborted = true)
    socket.close()
    assert(aborted)

  test("close restores interruption and still aborts instead of throwing"):
    var aborted = false
    val peer = new Peer(Nil, Some(new InterruptedException("teardown")))
    val socket = new SttpTransport(new SyncWebSocket(peer), 1024, 1.second, () => aborted = true)
    socket.close()
    assert(aborted)
    assert(Thread.currentThread().isInterrupted)
    val _ = Thread.interrupted()

  test("incoming limit counts large fragments without materializing encoded copies"):
    val fragment = "é" * 3000
    val peer = new Peer(List(WebSocketFrame.Text(fragment, false, None), WebSocketFrame.text("é")))
    assertEquals(transport(peer, 6002).receive(), Right(fragment + "é"))

  test("unpaired surrogates count their replacement byte exactly like getBytes"):
    val lone = "a\uD800"
    assertEquals(lone.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, 2)
    assertEquals(transport(new Peer(List(WebSocketFrame.text(lone))), 2).receive(), Right(lone))

  /** Models a foreign backend whose write ignores interruption but unblocks when its socket is aborted. */
  private final class BlockedPeer(frames: List[WebSocketFrame]) extends WebSocket[Identity]:
    private val started = ox.channels.Channel.buffered[Unit](1)
    private val released = new java.util.concurrent.Semaphore(0)
    private var remaining = frames
    val finished = new java.util.concurrent.atomic.AtomicBoolean(false)
    override def receive(): WebSocketFrame =
      val frame = remaining.head
      remaining = remaining.tail
      frame
    override def send(frame: WebSocketFrame, isContinuation: Boolean): Unit =
      val _ = started.trySendOrClosed(())
      released.acquireUninterruptibly()
      finished.set(true)
    def awaitWrite(): Unit = started.receive()
    def abort(): Unit = released.release(10)
    override def isOpen(): Boolean = true
    override val upgradeHeaders: Headers = Headers(Nil)
    override implicit val monad: MonadError[Identity] = IdentityMonad

  private def blockedTransport(peer: BlockedPeer): SttpTransport =
    new SttpTransport(new SyncWebSocket(peer), 1024, 40.millis, () => peer.abort(), 40.millis)

  test("write deadline aborts an uninterruptible foreign send before joining its worker"):
    val peer = new BlockedPeer(Nil)
    val result = ox.timeout(2.seconds)(blockedTransport(peer).send("{}"))
    assertEquals(result, Left(ObsError.Timeout("write")))
    assert(peer.finished.get())

  test("pong write deadline terminates receive without waiting for the next frame"):
    val peer = new BlockedPeer(List(WebSocketFrame.ping))
    assertEquals(ox.timeout(2.seconds)(blockedTransport(peer).receive()), Left(ObsError.Timeout("write")))
    assert(peer.finished.get())

  test("close deadline aborts an uninterruptible close before joining its worker"):
    val peer = new BlockedPeer(Nil)
    ox.timeout(2.seconds)(blockedTransport(peer).close())
    assert(peer.finished.get())

  test("caller interruption aborts an in-progress write before the scope can join it"):
    val peer = new BlockedPeer(Nil)
    ox.supervised:
      val sending = ox.forkCancellable(blockedTransport(peer).send("{}"))
      peer.awaitWrite()
      val _ = sending.cancel()
      assert(peer.finished.get())
