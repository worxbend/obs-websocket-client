package com.worxbend.obs.websocket.client

import com.worxbend.obs.websocket.client.protocol.*
import com.worxbend.obs.websocket.client.SessionWire.malformed
import ox.channels.Channel
import ox.discard

/** Mutable state is confined exclusively to an Ox Actor. No user callbacks run here. */
private[client] final class SessionLogic(
    config: ObsConfig,
    authenticationPassword: Option[String],
    outgoing: Channel[WireMessage],
    identified: Channel[Either[ObsError, ConnectionMetadata]]
):
  private case class Pending(requestType: String, opcode: Int, reply: Channel[Either[ObsError, JsonObject]])
  private case class Subscriber(
      channel: Channel[Event],
      types: Set[String],
      policy: OverflowPolicy,
      dropped: Long,
      representation: EventRepresentation
  )
  private case class State(
      phase: ConnectionState = ConnectionState.AwaitingHello,
      version: String = "",
      failure: Option[ObsError] = None,
      pending: Map[String, Pending] = Map.empty,
      subscribers: Map[String, Subscriber] = Map.empty,
      terminalDrops: Map[String, Long] = Map.empty
  )
  private var state = State()

  def phase: ConnectionState = state.phase

  def transition(to: ConnectionState): Unit =
    ConnectionState.transition(state.phase, to) match
      case Right(next) => state = state.copy(phase = next)
      case Left(error) => fail(error)

  def fail(error: ObsError): Unit =
    if state.failure.isEmpty then
      state.pending.values.foreach(_.reply.trySend(Left(error)).discard)
      state.subscribers.values.foreach(_.channel.errorOrClosed(SessionTerminated(error)).discard)
      identified.trySendOrClosed(Left(error)).discard
      outgoing.errorOrClosed(SessionTerminated(error)).discard
      state = state.copy(
        phase = ConnectionState.Failed,
        failure = Some(error),
        pending = Map.empty,
        subscribers = Map.empty,
        terminalDrops = state.subscribers.map((id, subscriber) => id -> subscriber.dropped)
      )

  def close(): Unit =
    if state.phase != ConnectionState.Closed then
      fail(ObsError.Closed)
      state = state.copy(phase = ConnectionState.Closed)

  private def queue(message: WireMessage): Either[ObsError, Unit] =
    outgoing.trySendOrClosed(message) match
      case true  => Right(())
      case false => Left(ObsError.Overflow("outgoing queue"))
      case _     => Left(state.failure.getOrElse(ObsError.Closed))

  def register(
      id: String,
      requestType: String,
      opcode: Int,
      message: WireMessage,
      reply: Channel[Either[ObsError, JsonObject]]
  ): Either[ObsError, Unit] =
    if state.phase != ConnectionState.Ready then Left(state.failure.getOrElse(ObsError.Closed))
    else if state.pending.contains(id) then Left(ObsError.InternalError("Request ID generator produced a duplicate ID"))
    else if state.pending.size >= config.maxInFlight then Left(ObsError.Overflow("in-flight requests"))
    else
      state = state.copy(pending = state.pending.updated(id, Pending(requestType, opcode, reply)))
      queue(message) match
        case left @ Left(_) =>
          cancel(id, reply)
          left
        case right => right

  def cancel(id: String, owner: Channel[Either[ObsError, JsonObject]]): Unit =
    if state.pending.get(id).exists(_.reply eq owner) then state = state.copy(pending = state.pending - id)

  /** A queued request that timed out before the writer claimed it must not be sent. */
  def canSend(message: WireMessage): Boolean =
    if state.failure.nonEmpty then false
    else if message.op == 6 || message.op == 8 then message.data.string("requestId").exists(state.pending.contains)
    else true

  /** A deterministic local send rejection completes only the affected request; the session stays alive. Messages
    * without a correlatable pending request fail the session, since Identify or Reidentify cannot be recovered.
    */
  def sendRejected(message: WireMessage, error: ObsError): Unit =
    val affected =
      if message.op == 6 || message.op == 8 then message.data.string("requestId").toOption else None
    affected match
      case Some(id) =>
        state.pending
          .get(id)
          .foreach: pending =>
            state = state.copy(pending = state.pending - id)
            pending.reply.trySend(Left(error)).discard
      case None => if message.op != 6 && message.op != 8 then fail(error)

  def reidentify(mask: EventSubscriptions): Either[ObsError, Unit] =
    if state.phase != ConnectionState.Ready then Left(state.failure.getOrElse(ObsError.Closed))
    else queue(WireMessage(3, JsonObject(Map("eventSubscriptions" -> JsonValue.Num(BigDecimal(mask.value))))))

  def subscribe(
      id: String,
      channel: Channel[Event],
      types: Set[String],
      policy: OverflowPolicy,
      representation: EventRepresentation = EventRepresentation.Typed
  ): Either[ObsError, Unit] =
    if state.phase != ConnectionState.Ready then Left(state.failure.getOrElse(ObsError.Closed))
    else if state.subscribers.contains(id) then Left(ObsError.InternalError("Duplicate subscription ID"))
    else
      state =
        state.copy(subscribers = state.subscribers.updated(id, Subscriber(channel, types, policy, 0L, representation)))
      Right(())

  def unsubscribe(id: String, owner: Channel[Event]): Unit =
    if state.subscribers.get(id).exists(_.channel eq owner) then
      owner.doneOrClosed().discard
      state = state.copy(subscribers = state.subscribers - id)

  def losses(id: String): Long =
    state.subscribers.get(id).map(_.dropped).orElse(state.terminalDrops.get(id)).getOrElse(0L)

  /** Returns whether the reader should keep consuming frames. */
  def incoming(message: WireMessage): Boolean =
    val handled = (state.phase, message.op) match
      case (ConnectionState.AwaitingHello, 0)                   => hello(message.data)
      case (ConnectionState.Identifying, 2)                     => ready(message.data)
      case (ConnectionState.Ready, 5)                           => event(message.data)
      case (ConnectionState.Ready, 7 | 9)                       => response(message)
      case (ConnectionState.Failed | ConnectionState.Closed, _) => Right(())
      case _ => Left(ObsError.UnexpectedMessage(state.phase, message.op))
    handled.left.foreach(fail)
    state.phase != ConnectionState.Failed && state.phase != ConnectionState.Closed

  private def hello(data: JsonObject): Either[ObsError, Unit] =
    for
      rpc <- data.int("rpcVersion").left.map(malformed)
      version <- data.string("obsWebSocketVersion").left.map(malformed)
      _ <- if rpc >= 1 then Right(()) else Left(ObsError.IncompatibleProtocol(rpc))
      auth <- data.fields.get("authentication") match
        case None                   => Right(Map.empty[String, JsonValue])
        case Some(auth: JsonObject) =>
          for
            salt <- auth.string("salt").left.map(malformed)
            challenge <- auth.string("challenge").left.map(malformed)
            password <- authenticationPassword.toRight(ObsError.Authentication("Server requires a password"))
          yield Map("authentication" -> JsonValue.Str(Authentication.compute(password, salt, challenge)))
        case _ => Left(ObsError.MalformedPayload("authentication", "Expected authentication object"))
      _ <- queue(
        WireMessage(
          1,
          JsonObject(
            auth ++ Map(
              "rpcVersion" -> JsonValue.Num(BigDecimal(1)),
              "eventSubscriptions" -> JsonValue.Num(BigDecimal(config.eventSubscriptions.value))
            )
          )
        )
      )
    yield
      state = state.copy(version = version)
      transition(ConnectionState.Identifying)

  private def ready(data: JsonObject): Either[ObsError, Unit] =
    data
      .int("negotiatedRpcVersion")
      .left
      .map(malformed)
      .flatMap: rpc =>
        if rpc != 1 then Left(ObsError.IncompatibleProtocol(rpc))
        else
          transition(ConnectionState.Ready)
          identified.trySend(Right(ConnectionMetadata(state.version, rpc, Set.empty))).discard
          Right(())

  private def response(message: WireMessage): Either[ObsError, Unit] =
    message.data
      .string("requestId")
      .left
      .map(malformed)
      .flatMap: id =>
        state.pending.get(id) match
          case None          => Right(()) // duplicate or late response: never revive a completed request
          case Some(pending) =>
            if pending.opcode != message.op then
              Left(ObsError.MalformedPayload("op", "Response opcode does not match request"))
            else
              val result = if message.op == 9 then Right(message.data)
              else SessionWire.responseData(message.data, pending.requestType, id)
              state = state.copy(pending = state.pending - id)
              pending.reply.trySend(result).discard
              Right(())

  private def event(data: JsonObject): Either[ObsError, Unit] =
    for
      eventType <- data.string("eventType").left.map(malformed)
      _ <- data
        .required("eventIntent", ValueCodec.number)
        .left
        .map(malformed)
        .flatMap: intent =>
          if intent.isWhole && intent >= 0 then Right(())
          else Left(ObsError.MalformedPayload("eventIntent", "Expected a nonnegative integer subscription mask"))
      eventData <- data.fields.get("eventData") match
        case None                    => Right(JsonObject.empty)
        case Some(value: JsonObject) => Right(value)
        case _                       => Left(ObsError.MalformedPayload("eventData", "Expected object"))
      decoded <- Event.decode(eventType, eventData).left.map(malformed)
    yield state.subscribers.foreach: (id, subscriber) =>
      if subscriber.types.isEmpty || subscriber.types.contains(eventType) then
        val delivered = subscriber.representation match
          case EventRepresentation.Typed => decoded
          case EventRepresentation.Raw   => UnknownEvent(eventType, eventData)
        subscriber.channel.trySendOrClosed(delivered) match
          case true  => ()
          case false =>
            subscriber.policy match
              case OverflowPolicy.Fail =>
                subscriber.channel.errorOrClosed(SessionTerminated(ObsError.Overflow("event subscription"))).discard
                state = state.copy(subscribers = state.subscribers - id)
              case OverflowPolicy.DropNewest =>
                state = state.copy(subscribers =
                  state.subscribers.updated(id, subscriber.copy(dropped = subscriber.dropped + 1))
                )
              case OverflowPolicy.DropOldest =>
                val dropped = SubscriptionDelivery.replaceOldest(
                  () => subscriber.channel.tryReceiveOrClosed(),
                  () => subscriber.channel.trySendOrClosed(delivered)
                )
                state = state.copy(subscribers =
                  state.subscribers.updated(id, subscriber.copy(dropped = subscriber.dropped + dropped))
                )
          case _ => state = state.copy(subscribers = state.subscribers - id)
