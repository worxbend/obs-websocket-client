package com.worxbend.obs.websocket.client

import com.worxbend.obs.websocket.client.protocol.*
import com.worxbend.obs.websocket.client.util.{Defect, Utf8}
import ox.*
import ox.channels.{Actor, BufferCapacity, Channel, ChannelClosed, ChannelClosedException}
import ox.either.catching
import scala.util.control.NonFatal

object ObsClient:
  /** Reader and writer forks, user request fibers and cleanup tells all post to the actor; 256 in-flight requests can
    * each contribute a registration plus a completion tell in one burst. Invocations never block mid-invocation, so a
    * larger mailbox only absorbs bursts where the Ox default of 16 would throttle senders.
    */
  private val mailboxCapacity: BufferCapacity = BufferCapacity(1024)

  /** Owns one connection. All workers terminate before this method returns. The socket is closed in the scope body
    * before Ox interrupts and joins its reader. Reconnection and automatic request replay are deliberately disabled.
    *
    * Expected failures — transport loss, protocol violations, timeouts — are reported as `Left(ObsError)`. An exception
    * thrown by the `use` callback is a defect: it propagates raw after the transport is closed, never wrapped in an
    * `ObsError`.
    */
  def withTransport[A](
    transport:    ObsTransport,
    config:       ObsConfig = ObsConfig(),
    dependencies: SessionDependencies = SessionDependencies.live,
  )(use: ObsSession => A): Either[ObsError, A] =
    supervised:
      try
        for
          valid    <- config.validate
          password <- valid.passwordProvider.password()
          result   <- run(transport = transport, config = valid, password = password, dependencies = dependencies)(use =
                      use
                    )
        yield result
      // Ordinary cleanup defects must not mask the result, but interruption and fatal errors still propagate.
      finally
        try transport.close()
        catch case NonFatal(_) => ()

  private def run[A](
    transport:    ObsTransport,
    config:       ObsConfig,
    password:     Option[String],
    dependencies: SessionDependencies,
  )(use: ObsSession => A)(using Ox): Either[ObsError, A] =
    val outgoing         = Channel.buffered[WireMessage](config.outgoingCapacity)
    val identified       = Channel.buffered[Either[ObsError, ConnectionMetadata]](1)
    given BufferCapacity = mailboxCapacity
    val logic            = Actor.create(
      new SessionLogic(config = config, authenticationPassword = password, outgoing = outgoing, identified = identified)
    )
    forkDiscard:
      try
        var running = true
        while running do
          outgoing.receiveOrClosed() match
            case message: WireMessage =>
              if logic.ask(_.canSend(message = message)) then
                val encoded = Protocol.encode(message = message)
                transport.send(text = encoded) match
                  case Left(error: ObsError.MessageTooLarge) =>
                    // Deterministic local rejection: nothing reached the socket, so only the
                    // offending request or batch fails and the session keeps running.
                    logic.ask(_.sendRejected(message = message, error = error))
                  case Left(error) =>
                    logic.ask(_.fail(error = error))
                    running = false
                  case Right(_) =>
                    logic.ask(
                      _.traffic(direction = TrafficDirection.Sent, bytes = Utf8.encodedLength(text = encoded).toInt)
                    )
            case _: ChannelClosed => running = false
      catch
        // A defect here is a client bug, not a retryable transport fault; keep the cause for diagnosis.
        case NonFatal(cause) =>
          logic.ask(
            _.fail(error =
              ObsError.InternalError(message = s"Transport send defect: ${Defect.describe(cause = cause)}")
            )
          )
    forkDiscard:
      try
        var running = true
        while running do
          transport.receive() match
            case Left(error) =>
              logic.ask(_.transportFailed(error = error))
              running = false
            case Right(text) =>
              // One actor round-trip per frame: byte accounting rides along with the decode outcome.
              running = logic.ask(
                _.received(
                  bytes   = Utf8.encodedLength(text = text).toInt,
                  decoded =
                    Protocol.decode(text = text, maxBytes = config.maxMessageBytes).left.map(SessionWire.malformed),
                )
              )
      catch
        case NonFatal(cause) =>
          logic.ask(
            _.fail(error =
              ObsError.InternalError(message = s"Transport receive defect: ${Defect.describe(cause = cause)}")
            )
          )
    try
      timeoutOption(config.handshakeTimeout)(identified.receive())
        .getOrElse(Left(ObsError.Timeout(operation = "handshake")))
        .flatMap: initial =>
          val provisional =
            new ObsSession(metadata = initial, config = config, dependencies = dependencies, logic = logic)
          provisional.discoverVersion
            .flatMap: version =>
              version
                .array(name = "availableRequests")
                .left
                .map(SessionWire.malformed)
                .flatMap: values =>
                  values.foldLeft[Either[ObsError, Set[String]]](Right(Set.empty)):
                    case (acc, JsonValue.Str(value)) => acc.map(_ + value)
                    case (_, _)                      =>
                      Left(ObsError.MalformedPayload(path = "availableRequests", message = "Expected request names"))
                .map: available =>
                  use(
                    new ObsSession(
                      metadata     = initial.copy(availableRequests = available),
                      config       = config,
                      dependencies = dependencies,
                      logic        = logic,
                    )
                  )
    finally uninterruptible(logic.ask(_.close()).catching[ChannelClosedException].discard)
