package com.worxbend.obs.websocket.client.transport.pekko

import com.worxbend.obs.websocket.client.ObsError
import java.io.IOException
import munit.FunSuite
import org.apache.pekko.http.scaladsl.model.ws.PeerClosedConnectionException
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*
import sttp.monad.FutureMonad
import sttp.model.Headers
import sttp.monad.MonadError
import sttp.ws.{WebSocket, WebSocketClosed, WebSocketFrame}

class PekkoTransportSuite extends FunSuite:
  private val incomingSizeLimitMessage: String = "Incoming message exceeds configured byte limit"
  private val closedWebSocketMessage: String   = "WebSocket closed"

  final private class Peer(frames: List[WebSocketFrame], failure: Option[Throwable] = None) extends WebSocket[Future]:
    @volatile private var remaining                = frames
    @volatile var sent: List[WebSocketFrame]       = Nil
    override def receive(): Future[WebSocketFrame] = failure match
      case Some(error) => Future.failed(error)
      case None        =>
        Future.successful:
          val frame = remaining.head
          remaining = remaining.tail
          frame
    override def send(frame: WebSocketFrame, isContinuation: Boolean): Future[Unit] = failure match
      case Some(error) => Future.failed(error)
      case None        =>
        Future.successful:
          sent = sent :+ frame
    override def isOpen(): Future[Boolean]          = Future.successful(true)
    override val upgradeHeaders: Headers            = Headers(Nil)
    implicit override val monad: MonadError[Future] = new FutureMonad()(using ExecutionContext.parasitic)

  private def transport(peer: Peer, limit: Int = 1024): PekkoTransport =
    new PekkoTransport(socket = peer, maxMessageBytes = limit, shutdownTimeout = 1.second, () => ())

  test("fragmented text is reassembled across ping and pong control frames"):
    val ping = Array[Byte](1, 2, 3)
    val peer = new Peer(
      frames = List(
        WebSocketFrame.Text("{", false, None),
        WebSocketFrame.Ping(ping),
        WebSocketFrame.pong,
        WebSocketFrame.text("}"),
      )
    )
    assertEquals(transport(peer = peer).receive(), Right("{}"))
    assertEquals(peer.sent, List(WebSocketFrame.Pong(ping)))

  test("incoming limit counts UTF-8 bytes across fragments"):
    val peer = new Peer(frames = List(WebSocketFrame.Text("é", false, None), WebSocketFrame.text("é")))
    assertEquals(
      transport(peer = peer, limit = 3).receive(),
      Left(ObsError.MessageTooLarge(message = incomingSizeLimitMessage)),
    )

  test("exact byte limit is accepted"):
    assertEquals(transport(peer = new Peer(frames = List(WebSocketFrame.text("é"))), limit = 2).receive(), Right("é"))

  test("binary frames fail explicitly"):
    assertEquals(
      transport(peer = new Peer(frames = List(WebSocketFrame.binary(Array[Byte](1))))).receive(),
      Left(ObsError.UnsupportedMessage(message = "Binary messages are unsupported; use OBS JSON encoding")),
    )

  test("close frame preserves status without reflecting peer-controlled text"):
    assertEquals(
      transport(peer = new Peer(frames = List(WebSocketFrame.Close(4009, "secret")))).receive(),
      Left(ObsError.Transport(message = closedWebSocketMessage, closeCode = Some(4009))),
    )

  test("backend close exception preserves status"):
    val peer = new Peer(frames = Nil, failure = Some(WebSocketClosed(Some(WebSocketFrame.Close(4011, "secret")))))
    assertEquals(
      transport(peer = peer).receive(),
      Left(ObsError.Transport(message = closedWebSocketMessage, closeCode = Some(4011))),
    )

  test("pekko peer-close exception preserves status without reflecting peer-controlled text"):
    val peer = new Peer(
      frames  = Nil,
      failure = Some(new PeerClosedConnectionException(4011, "secret")),
    )
    assertEquals(
      transport(peer = peer).receive(),
      Left(ObsError.Transport(message = closedWebSocketMessage, closeCode = Some(4011))),
    )

  test("abrupt close without a frame is represented"):
    assertEquals(
      transport(peer = new Peer(frames = Nil, failure = Some(WebSocketClosed(None)))).receive(),
      Left(ObsError.Transport(message = closedWebSocketMessage, closeCode = None)),
    )

  test("I/O exception diagnostics are redacted"):
    assertEquals(
      transport(peer = new Peer(frames = Nil, failure = Some(new IOException("secret")))).receive(),
      Left(ObsError.Transport(message = "WebSocket I/O failed")),
    )

  test("outgoing byte limit rejects before sending"):
    val peer = new Peer(frames = Nil)
    assertEquals(
      transport(peer = peer, limit = 1).send(text = "é"),
      Left(ObsError.MessageTooLarge(message = "Outgoing message exceeds configured byte limit")),
    )
    assertEquals(peer.sent, Nil)

  test("send forwards text unchanged"):
    val peer = new Peer(frames = Nil)
    assertEquals(transport(peer = peer).send(text = "{}"), Right(()))
    assertEquals(peer.sent, List(WebSocketFrame.text("{}")))

  test("close sends the normal close control frame"):
    val peer = new Peer(frames = Nil)
    transport(peer = peer).close()
    assertEquals(peer.sent, List(WebSocketFrame.close))

  test("forced cleanup runs even when close fails"):
    var aborted = false
    val peer    = new Peer(frames = Nil, failure = Some(new IOException("close failed")))
    val socket  = new PekkoTransport(
      socket          = peer,
      maxMessageBytes = 1024,
      shutdownTimeout = 1.second,
      () => aborted = true,
    )
    socket.close()
    assert(aborted)

  test("close restores interruption and still aborts instead of throwing"):
    var aborted = false
    val peer    = new Peer(frames = Nil, failure = Some(new InterruptedException("teardown")))
    val socket  = new PekkoTransport(
      socket          = peer,
      maxMessageBytes = 1024,
      shutdownTimeout = 1.second,
      () => aborted = true,
    )
    socket.close()
    assert(aborted)
    assert(Thread.currentThread().isInterrupted)
    val _ = Thread.interrupted()

  test("incoming limit counts large fragments without materializing encoded copies"):
    val fragment = "é" * 3000
    val peer     = new Peer(frames = List(WebSocketFrame.Text(fragment, false, None), WebSocketFrame.text("é")))
    assertEquals(transport(peer = peer, limit = 6002).receive(), Right(fragment + "é"))

  test("unpaired surrogates count three bytes like the U+FFFD an encoder emits"):
    val lone = "a\uD800"
    assertEquals(transport(peer = new Peer(frames = List(WebSocketFrame.text(lone))), limit = 4).receive(), Right(lone))
    assertEquals(
      transport(peer = new Peer(frames = List(WebSocketFrame.text(lone))), limit = 3).receive(),
      Left(ObsError.MessageTooLarge(message = incomingSizeLimitMessage)),
    )

  test("multi-byte characters at the exact limit pass while one byte over fails"):
    val threeBytes = "€"            // U+20AC
    val fourBytes  = "\uD83D\uDE00" // U+1F600, a surrogate pair
    assertEquals(
      transport(peer = new Peer(frames = List(WebSocketFrame.text(threeBytes))), limit = 3).receive(),
      Right(threeBytes),
    )
    assertEquals(
      transport(peer = new Peer(frames = List(WebSocketFrame.text(threeBytes))), limit = 2).receive(),
      Left(ObsError.MessageTooLarge(message = incomingSizeLimitMessage)),
    )
    assertEquals(
      transport(peer = new Peer(frames = List(WebSocketFrame.text(fourBytes))), limit = 4).receive(),
      Right(fourBytes),
    )
    assertEquals(
      transport(peer = new Peer(frames = List(WebSocketFrame.text(fourBytes))), limit = 3).receive(),
      Left(ObsError.MessageTooLarge(message = incomingSizeLimitMessage)),
    )

  test("outgoing byte limit accepts multi-byte text exactly at the limit"):
    val peer = new Peer(frames = Nil)
    assertEquals(transport(peer = peer, limit = 2).send(text = "é"), Right(()))
    assertEquals(peer.sent, List(WebSocketFrame.text("é")))
    assertEquals(
      transport(peer = new Peer(frames = Nil), limit = 2).send(text = "€"),
      Left(ObsError.MessageTooLarge(message = "Outgoing message exceeds configured byte limit")),
    )

  /** Models a foreign backend whose write ignores interruption but unblocks when its socket is aborted. The foreign
    * write runs on a foreign executor thread, which scope joins cannot await, so completion is signalled through a latch.
    */
  final private class BlockedPeer(frames: List[WebSocketFrame]) extends WebSocket[Future]:
    private val started                            = ox.channels.Channel.buffered[Unit](1)
    private val released                           = new java.util.concurrent.Semaphore(0)
    @volatile private var remaining                = frames
    val finished                                   = new java.util.concurrent.CountDownLatch(1)
    override def receive(): Future[WebSocketFrame] =
      Future.successful:
        val frame = remaining.head
        remaining = remaining.tail
        frame
    override def send(frame: WebSocketFrame, isContinuation: Boolean): Future[Unit] =
      Future {
        val _ = started.trySendOrClosed(())
        released.acquireUninterruptibly()
        val _ = finished.countDown()
      }(using ExecutionContext.global)
    def awaitWrite(): Unit                          = started.receive()
    def abort(): Unit                               = released.release(10)
    override def isOpen(): Future[Boolean]          = Future.successful(true)
    override val upgradeHeaders: Headers            = Headers(Nil)
    implicit override val monad: MonadError[Future] = new FutureMonad()(using ExecutionContext.parasitic)

  private def blockedTransport(peer: BlockedPeer): PekkoTransport =
    new PekkoTransport(
      socket          = peer,
      maxMessageBytes = 1024,
      shutdownTimeout = 40.millis,
      () => peer.abort(),
      writeTimeout = 40.millis,
    )

  test("write deadline aborts an uninterruptible foreign send before joining its worker"):
    val peer   = new BlockedPeer(frames = Nil)
    val result = ox.timeout(2.seconds)(blockedTransport(peer = peer).send(text = "{}"))
    assertEquals(result, Left(ObsError.Timeout(operation = "write")))
    assert(peer.finished.await(2, java.util.concurrent.TimeUnit.SECONDS))

  test("pong write deadline terminates receive without waiting for the next frame"):
    val peer = new BlockedPeer(frames = List(WebSocketFrame.ping))
    assertEquals(
      ox.timeout(2.seconds)(blockedTransport(peer = peer).receive()),
      Left(ObsError.Timeout(operation = "write")),
    )
    assert(peer.finished.await(2, java.util.concurrent.TimeUnit.SECONDS))

  test("close deadline aborts an uninterruptible close before joining its worker"):
    val peer = new BlockedPeer(frames = Nil)
    ox.timeout(2.seconds)(blockedTransport(peer = peer).close())
    assert(peer.finished.await(2, java.util.concurrent.TimeUnit.SECONDS))

  test("caller interruption aborts an in-progress write before the scope can join it"):
    val peer = new BlockedPeer(frames = Nil)
    ox.supervised:
      val sending = ox.forkCancellable(blockedTransport(peer = peer).send(text = "{}"))
      peer.awaitWrite()
      val _ = sending.cancel()
      assert(peer.finished.await(2, java.util.concurrent.TimeUnit.SECONDS))

  /** Models a foreign backend whose read blocks indefinitely until its socket is aborted. */
  final private class SilentPeer(frames: List[WebSocketFrame] = Nil) extends WebSocket[Future]:
    private val started                            = ox.channels.Channel.buffered[Unit](1)
    private val gate                               = new java.util.concurrent.Semaphore(0)
    @volatile private var remaining                = frames
    @volatile var aborted                          = false
    override def receive(): Future[WebSocketFrame] =
      Future {
        if remaining.nonEmpty then
          val frame = remaining.head
          remaining = remaining.tail
          frame
        else
          val _ = started.trySendOrClosed(())
          gate.acquireUninterruptibly()
          throw new IOException("read aborted")
      }(using ExecutionContext.global)
    override def send(frame: WebSocketFrame, isContinuation: Boolean): Future[Unit] = Future.successful(())
    def awaitRead(): Unit                                                           = started.receive()
    def abort(): Unit                                                               =
      aborted = true
      gate.release(10)
    override def isOpen(): Future[Boolean]          = Future.successful(true)
    override val upgradeHeaders: Headers            = Headers(Nil)
    implicit override val monad: MonadError[Future] = new FutureMonad()(using ExecutionContext.parasitic)

  private def idleTransport(
    peer:            SilentPeer,
    deadline:        FiniteDuration,
    shutdownTimeout: FiniteDuration = 1.second,
  ): PekkoTransport =
    new PekkoTransport(
      socket          = peer,
      maxMessageBytes = 1024,
      shutdownTimeout = shutdownTimeout,
      () => peer.abort(),
      writeTimeout    = 1.second,
      readIdleTimeout = Some(deadline),
    )

  test("read idle deadline aborts a silent peer and surfaces a retryable timeout"):
    val peer   = new SilentPeer
    val result = ox.timeout(2.seconds)(idleTransport(peer = peer, deadline = 40.millis).receive())
    assertEquals(result, Left(ObsError.Timeout(operation = "read")))
    result.left.foreach(error =>
      assert(
        com.worxbend.obs.websocket.client.reconnect.ReconnectPolicy.retryable(error = error)
      )
    )
    assert(peer.aborted)

  test("traffic within the idle deadline receives normally"):
    val peer = new SilentPeer(frames = List(WebSocketFrame.Text("{", false, None), WebSocketFrame.text("}")))
    assertEquals(idleTransport(peer = peer, deadline = 5.seconds).receive(), Right("{}"))
    assert(!peer.aborted)

  test("a stall between fragments trips the idle deadline"):
    val peer = new SilentPeer(frames = List(WebSocketFrame.Text("{", false, None)))
    assertEquals(
      ox.timeout(2.seconds)(idleTransport(peer = peer, deadline = 40.millis).receive()),
      Left(ObsError.Timeout(operation = "read")),
    )
    assert(peer.aborted)

  test("close unblocks a receive waiting inside the idle deadline"):
    val peer      = new SilentPeer
    val transport = idleTransport(peer = peer, deadline = 10.seconds, shutdownTimeout = 40.millis)
    ox.timeout(2.seconds):
      ox.supervised:
        val receiving = ox.fork(transport.receive())
        peer.awaitRead()
        transport.close()
        assertEquals(receiving.join(), Left(ObsError.Transport(message = "WebSocket I/O failed")))
    assert(peer.aborted)

  test("caller interruption aborts a receive waiting inside the idle deadline"):
    val peer      = new SilentPeer
    val transport = idleTransport(peer = peer, deadline = 10.seconds)
    ox.supervised:
      val receiving = ox.forkCancellable(transport.receive())
      peer.awaitRead()
      val _ = receiving.cancel()
    assert(peer.aborted)
