package com.worxbend.obs.websocket.client.transport

import com.worxbend.obs.websocket.client.ObsError
import java.util.concurrent.CancellationException
import munit.FunSuite
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.*

class AbstractObsTransportSuite extends FunSuite:
  private val incomingSizeLimitMessage: String = "Incoming message exceeds configured byte limit"
  private val closedWebSocketMessage: String   = "WebSocket closed"

  /** Scripted in-memory adapter: every receive/send consults a mutable script so each loop branch is drivable. */
  private class ScriptedTransport(
    maxMessageBytes: Int = 1024,
    shutdownTimeout: FiniteDuration = 1.second,
    writeTimeout:    FiniteDuration = 1.second,
    readIdleTimeout: Option[FiniteDuration] = None,
    onAbort:         () => Unit = () => (),
  ) extends AbstractObsTransport(
        maxMessageBytes = maxMessageBytes,
        shutdownTimeout = shutdownTimeout,
        abortConnection = onAbort,
        writeTimeout    = writeTimeout,
        readIdleTimeout = readIdleTimeout,
      ):
    val frames                                   = ArrayBuffer.empty[Either[ObsError, TransportFrame]]
    val sent                                     = ArrayBuffer.empty[String]
    var pongs                                    = Vector.empty[Array[Byte]]
    var closes                                   = 0
    var onSendText: () => Either[ObsError, Unit] = () => Right(())
    var onSendPong: () => Either[ObsError, Unit] = () =>
      pongs = pongs :+ Array.emptyByteArray
      Right(())
    var onSendClose: () => Either[ObsError, Unit] = () =>
      closes += 1
      Right(())

    override protected def receiveFrame(): Either[ObsError, TransportFrame] =
      val frame = frames.head
      frames.dropInPlace(1)
      frame
    override protected def sendTextFrame(text: String): Either[ObsError, Unit] =
      onSendText() match
        case success @ Right(_) =>
          sent += text
          success
        case failure => failure
    override protected def sendPongFrame(payload: Array[Byte]): Either[ObsError, Unit] =
      onSendPong() match
        case success @ Right(_) =>
          pongs = pongs.updated(pongs.size - 1, payload)
          success
        case failure => failure
    override protected def sendCloseFrame(): Either[ObsError, Unit] = onSendClose()

  private def scripted(frames: Either[ObsError, TransportFrame]*): ScriptedTransport =
    val transport = new ScriptedTransport()
    transport.frames ++= frames
    transport

  test("fragments aggregate until the final fragment"):
    val transport = scripted(
      Right(TransportFrame.Text("{", finalFragment = false)),
      Right(TransportFrame.Pong),
      Right(TransportFrame.Text("}", finalFragment = true)),
    )
    assertEquals(transport.receive(), Right("{}"))

  test("incoming byte limit fails the whole message at the crossing fragment"):
    val transport = new ScriptedTransport(maxMessageBytes = 3)
    transport.frames ++= Seq(
      Right(TransportFrame.Text("é", finalFragment = false)),
      Right(TransportFrame.Text("é", finalFragment = true)),
    )
    assertEquals(transport.receive(), Left(ObsError.MessageTooLarge(message = incomingSizeLimitMessage)))

  test("incoming byte limit aborts the connection instead of draining further frames"):
    var aborts    = 0
    val transport = new ScriptedTransport(maxMessageBytes = 3, onAbort = () => aborts += 1)
    transport.frames ++= Seq(
      Right(TransportFrame.Text("é", finalFragment = false)),
      Right(TransportFrame.Text("é", finalFragment = false)), // crosses the limit
      Right(TransportFrame.Text("é", finalFragment = true)),
    )
    assertEquals(transport.receive(), Left(ObsError.MessageTooLarge(message = incomingSizeLimitMessage)))
    assertEquals(aborts, 1)
    assertEquals(transport.frames.size, 1, "The loop must not consume frames past the limit breach")

  test("ping is answered with an echoing bounded pong"):
    val payload   = Array[Byte](1, 2, 3)
    val transport = scripted(
      Right(TransportFrame.Ping(payload)),
      Right(TransportFrame.Text("{}", finalFragment = true)),
    )
    assertEquals(transport.receive(), Right("{}"))
    assertEquals(transport.pongs, Vector(payload))

  test("pong write failure fails the receive"):
    val transport = scripted(Right(TransportFrame.Ping(Array.emptyByteArray)))
    transport.onSendPong = () => Left(ObsError.Transport(message = "WebSocket I/O failed"))
    assertEquals(transport.receive(), Left(ObsError.Transport(message = "WebSocket I/O failed")))

  test("close frame preserves the status code"):
    val transport = scripted(Right(TransportFrame.Close(4009)))
    assertEquals(
      transport.receive(),
      Left(ObsError.Transport(message = closedWebSocketMessage, closeCode = Some(4009))),
    )

  test("binary frames fail explicitly"):
    val transport = scripted(Right(TransportFrame.Binary))
    assertEquals(
      transport.receive(),
      Left(ObsError.UnsupportedMessage(message = "Binary messages are unsupported; use OBS JSON encoding")),
    )

  test("adapter receive failures pass through"):
    val transport = scripted(Left(ObsError.Transport(message = closedWebSocketMessage, closeCode = None)))
    assertEquals(transport.receive(), Left(ObsError.Transport(message = closedWebSocketMessage, closeCode = None)))

  test("send forwards text and rejects oversized outgoing text before sending"):
    val transport = scripted()
    assertEquals(transport.send(text = "{}"), Right(()))
    assertEquals(transport.sent.toList, List("{}"))
    val limited = new ScriptedTransport(maxMessageBytes = 2)
    assertEquals(
      limited.send(text = "€"),
      Left(ObsError.MessageTooLarge(message = "Outgoing message exceeds configured byte limit")),
    )
    assertEquals(limited.sent.toList, Nil)

  test("send passes through a mapped write failure"):
    val transport = scripted()
    transport.onSendText = () => Left(ObsError.Transport(message = "WebSocket I/O failed"))
    assertEquals(transport.send(text = "{}"), Left(ObsError.Transport(message = "WebSocket I/O failed")))

  test("close sends the close frame, aborts, and stays safe to call twice"):
    var aborts    = 0
    val transport = new ScriptedTransport(onAbort = () => aborts += 1)
    transport.close()
    transport.close()
    assertEquals(transport.closes, 2)
    assertEquals(aborts, 2)

  test("write deadline aborts a stalled send and reports a retryable timeout"):
    var aborts    = 0
    val released  = new java.util.concurrent.Semaphore(0)
    val transport = new ScriptedTransport(
      writeTimeout = 40.millis,
      onAbort      = () =>
        aborts += 1
        released.release(),
    )
    transport.onSendText = () =>
      released.acquire()
      Left(ObsError.Transport(message = "WebSocket I/O failed"))
    assertEquals(ox.timeout(2.seconds)(transport.send(text = "{}")), Left(ObsError.Timeout(operation = "write")))
    assertEquals(aborts, 1)

  test("close deadline aborts a stalled close"):
    var aborts    = 0
    val released  = new java.util.concurrent.Semaphore(0)
    val transport = new ScriptedTransport(
      shutdownTimeout = 40.millis,
      onAbort         = () =>
        aborts += 1
        released.release(),
    )
    transport.onSendClose = () =>
      released.acquire()
      Left(ObsError.Transport(message = "WebSocket I/O failed"))
    ox.timeout(2.seconds)(transport.close())
    assertEquals(aborts, 2)

  test("read idle deadline aborts a silent reader"):
    var aborts   = 0
    val gate     = new java.util.concurrent.Semaphore(0)
    val blocking = new ScriptedTransport(
      readIdleTimeout = Some(40.millis),
      onAbort         = () =>
        aborts += 1
        gate.release(),
    ):
      override protected def receiveFrame(): Either[ObsError, TransportFrame] =
        gate.acquire()
        Left(ObsError.Transport(message = "WebSocket I/O failed"))
    assertEquals(ox.timeout(2.seconds)(blocking.receive()), Left(ObsError.Timeout(operation = "read")))
    assertEquals(aborts, 1)

  test("a stall between fragments trips the idle deadline"):
    val gate      = new java.util.concurrent.Semaphore(0)
    val transport = new ScriptedTransport(readIdleTimeout = Some(40.millis), onAbort = () => gate.release()):
      override protected def receiveFrame(): Either[ObsError, TransportFrame] =
        if frames.nonEmpty then super.receiveFrame()
        else
          gate.acquire()
          Left(ObsError.Transport(message = "WebSocket I/O failed"))
    transport.frames += Right(TransportFrame.Text("{", finalFragment = false))
    assertEquals(ox.timeout(2.seconds)(transport.receive()), Left(ObsError.Timeout(operation = "read")))

  test("caller interruption aborts an in-progress write and propagates"):
    val released  = new java.util.concurrent.Semaphore(0)
    var aborts    = 0
    val transport = new ScriptedTransport(onAbort = () =>
      aborts += 1
      released.release())
    transport.onSendText = () =>
      released.acquire()
      Left(ObsError.Transport(message = "WebSocket I/O failed"))
    ox.supervised:
      val sending = ox.forkCancellable(transport.send(text = "{}"))
      Thread.sleep(50)
      val _ = sending.cancel()
    assertEquals(aborts, 1)

  test("caller interruption aborts a receive waiting inside the idle deadline"):
    val started   = new java.util.concurrent.Semaphore(0)
    val released  = new java.util.concurrent.Semaphore(0)
    var aborts    = 0
    val transport = new ScriptedTransport(
      readIdleTimeout = Some(10.seconds),
      onAbort         = () =>
        aborts += 1
        released.release(10),
    ):
      override protected def receiveFrame(): Either[ObsError, TransportFrame] =
        started.release()
        released.acquireUninterruptibly()
        Left(ObsError.Transport(message = "WebSocket I/O failed"))
    ox.supervised:
      val receiving = ox.forkCancellable(transport.receive())
      started.acquire()
      val _ = receiving.cancel()
    assertEquals(aborts, 1)

  test("close restores interruption and still aborts instead of throwing"):
    var aborts    = 0
    val transport = new ScriptedTransport(onAbort = () => aborts += 1)
    transport.onSendClose = () => throw new InterruptedException("teardown")
    transport.close()
    assertEquals(aborts, 2)
    assert(Thread.currentThread().isInterrupted)
    val _ = Thread.interrupted()

  test("post-abort fork failures are mapped instead of flipping the result into a defect"):
    val started   = new java.util.concurrent.Semaphore(0)
    val released  = new java.util.concurrent.Semaphore(0)
    val result    = new java.util.concurrent.LinkedBlockingQueue[Either[ObsError, Unit]]()
    val transport = new ScriptedTransport(
      writeTimeout    = 10.seconds,
      shutdownTimeout = 1.second,
      onAbort         = () => released.release(10),
    )
    // The send signals its start, then fails with an unmapped exception type once the abort lands.
    transport.onSendText = () =>
      started.release()
      released.acquireUninterruptibly()
      throw new CancellationException("pool cancelled after abort")
    val sending = new Thread(() => result.put(transport.send(text = "{}")))
    sending.start()
    started.acquire()
    // close() aborts promptly: the Close write succeeds and the finally abort releases the stalled send.
    transport.close()
    sending.join(5000)
    assert(!sending.isAlive, "The guarded send must finish promptly")
    val sent = result.poll(5, java.util.concurrent.TimeUnit.SECONDS)
    assert(sent != null, "The send must produce a result instead of dying with the foreign exception")
    assertEquals(sent, Left(ObsError.Transport(message = closedWebSocketMessage, closeCode = None)))

  test("post-abort read failures are mapped instead of flipping the result into a defect"):
    val started   = new java.util.concurrent.Semaphore(0)
    val released  = new java.util.concurrent.Semaphore(0)
    val result    = new java.util.concurrent.LinkedBlockingQueue[Either[ObsError, String]]()
    val transport = new ScriptedTransport(
      shutdownTimeout = 1.second,
      readIdleTimeout = Some(10.seconds),
      onAbort         = () => released.release(10),
    ):
      override protected def receiveFrame(): Either[ObsError, TransportFrame] =
        started.release()
        released.acquireUninterruptibly()
        throw new CancellationException("stream cancelled after abort")
    val receiving = new Thread(() => result.put(transport.receive()))
    receiving.start()
    started.acquire()
    transport.close()
    receiving.join(5000)
    assert(!receiving.isAlive, "The guarded receive must finish promptly")
    val received = result.poll(5, java.util.concurrent.TimeUnit.SECONDS)
    assert(received != null, "The receive must produce a result instead of dying with the foreign exception")
    assertEquals(received, Left(ObsError.Transport(message = closedWebSocketMessage, closeCode = None)))

  test("unmapped failures before any abort still propagate as defects"):
    val transport = scripted()
    transport.onSendText = () => throw new IllegalStateException("foreign defect")
    intercept[IllegalStateException]:
      transport.send(text = "{}")
