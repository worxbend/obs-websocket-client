package com.worxbend.obs.websocket.client

import com.worxbend.obs.websocket.client.protocol.*
import ox.*
import ox.channels.{Actor, Channel, ChannelClosed, ChannelClosedException}
import ox.either.catching
import scala.util.control.NonFatal
import java.nio.charset.StandardCharsets.UTF_8

object ObsClient:
  /** Owns one connection. All workers terminate before this method returns. The socket is closed in the scope body
    * before Ox interrupts and joins its reader. Reconnection and automatic request replay are deliberately disabled.
    */
  def withTransport[A](
      transport: ObsTransport,
      config: ObsConfig = ObsConfig(),
      dependencies: SessionDependencies = SessionDependencies.live
  )(use: ObsSession => A): Either[ObsError, A] =
    supervised:
      try
        for
          valid <- config.validate
          password <- valid.passwordProvider.password()
          result <- run(transport, valid, password, dependencies)(use)
        yield result
      // Ordinary cleanup defects must not mask the result, but interruption and fatal errors still propagate.
      finally
        try transport.close()
        catch case NonFatal(_) => ()

  private def run[A](
      transport: ObsTransport,
      config: ObsConfig,
      password: Option[String],
      dependencies: SessionDependencies
  )(use: ObsSession => A)(using Ox): Either[ObsError, A] =
    val outgoing = Channel.buffered[WireMessage](config.outgoingCapacity)
    val identified = Channel.buffered[Either[ObsError, ConnectionMetadata]](1)
    val logic = Actor.create(new SessionLogic(config, password, outgoing, identified))
    forkDiscard:
      try
        var running = true
        while running do
          outgoing.receiveOrClosed() match
            case message: WireMessage =>
              if logic.ask(_.canSend(message)) then
                val encoded = Protocol.encode(message)
                transport.send(encoded) match
                  case Left(error: ObsError.MessageTooLarge) =>
                    // Deterministic local rejection: nothing reached the socket, so only the
                    // offending request or batch fails and the session keeps running.
                    logic.ask(_.sendRejected(message, error))
                  case Left(error) =>
                    logic.ask(_.fail(error))
                    running = false
                  case Right(_) => logic.ask(_.traffic(TrafficDirection.Sent, encoded.getBytes(UTF_8).length))
            case _: ChannelClosed => running = false
      catch case NonFatal(_) => logic.ask(_.fail(ObsError.Transport("Transport send failed unexpectedly")))
    forkDiscard:
      try
        var running = true
        while running do
          transport
            .receive()
            .flatMap: text =>
              logic.ask(_.traffic(TrafficDirection.Received, text.getBytes(UTF_8).length))
              Protocol.decode(text, config.maxMessageBytes).left.map(SessionWire.malformed)
          match
            case Left(error) =>
              val classified = error match
                case ObsError.Transport(_, Some(4009)) if logic.ask(_.phase) != ConnectionState.Ready =>
                  // 4009 (AuthenticationFailed) is only legitimate during Identify.
                  ObsError.Authentication("Server rejected authentication", Some(4009))
                case other => other
              logic.ask(_.fail(classified))
              running = false
            case Right(message) =>
              running = logic.ask(_.incoming(message))
      catch case NonFatal(_) => logic.ask(_.fail(ObsError.Transport("Transport receive failed unexpectedly")))
    try
      timeoutOption(config.handshakeTimeout)(identified.receive())
        .getOrElse(Left(ObsError.Timeout("handshake")))
        .flatMap: initial =>
          val provisional = new ObsSession(initial, config, dependencies, logic)
          provisional.discoverVersion
            .flatMap: version =>
              version
                .array("availableRequests")
                .left
                .map(SessionWire.malformed)
                .flatMap: values =>
                  values.foldLeft[Either[ObsError, Set[String]]](Right(Set.empty)):
                    case (acc, JsonValue.Str(value)) => acc.map(_ + value)
                    case (_, _) => Left(ObsError.MalformedPayload("availableRequests", "Expected request names"))
                .map: available =>
                  use(new ObsSession(initial.copy(availableRequests = available), config, dependencies, logic))
    finally uninterruptible(logic.ask(_.close()).catching[ChannelClosedException].discard)
