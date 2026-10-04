package com.worxbend.obs.websocket.client

import com.worxbend.obs.websocket.client.protocol.*
import com.worxbend.obs.websocket.client.SessionWire.malformed
import ox.channels.Channel
import ox.discard
import java.util.concurrent.atomic.AtomicLong

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
      dropped: AtomicLong,
      representation: EventRepresentation
  )
  private case class State(
      phase: ConnectionState = ConnectionState.AwaitingHello,
      version: String = "",
      reidentifyAcks: Int = 0,
      failure: Option[ObsError] = None,
      pending: Map[String, Pending] = Map.empty,
      subscribers: Map[String, Subscriber] = Map.empty,
      diagnostics: DiagnosticRegistry = DiagnosticRegistry(),
      stats: SessionStats = SessionStats()
  )
  private var state = State()

  def phase: ConnectionState = state.phase

  /** Reidentify acknowledgements still awaited from the server. */
  def pendingReidentifyAcks: Int = state.reidentifyAcks

  def transition(to: ConnectionState): Unit =
    ConnectionState.transition(state.phase, to) match
      case Right(next) =>
        state = state.copy(phase = next)
        publish(SessionDiagnostic.StateChanged(next))
      case Left(error) => fail(error)

  def fail(error: ObsError): Unit =
    if state.failure.isEmpty then
      val terminal = if error == ObsError.Closed then ConnectionState.Closed else ConnectionState.Failed
      publish(SessionDiagnostic.StateChanged(terminal))
      // Observers learn the concrete failure exactly like event subscribers do; a clean close completes them silently.
      if error == ObsError.Closed then state.diagnostics.close()
      else state.diagnostics.fail(error)
      state.pending.values.foreach(_.reply.trySend(Left(error)).discard)
      state.subscribers.values.foreach(_.channel.errorOrClosed(SessionTerminated(error)).discard)
      identified.trySendOrClosed(Left(error)).discard
      outgoing.errorOrClosed(SessionTerminated(error)).discard
      state = state.copy(
        phase = terminal,
        failure = Some(error),
        pending = Map.empty,
        subscribers = Map.empty
      )

  def close(): Unit =
    if state.phase != ConnectionState.Closed then
      fail(ObsError.Closed)
      state = state.copy(phase = ConnectionState.Closed)

  def statistics: SessionStats = state.stats

  private def publish(event: SessionDiagnostic): Unit =
    state = state.copy(diagnostics = state.diagnostics.publish(event))

  def traffic(direction: TrafficDirection, bytes: Int): Unit =
    val stats = direction match
      case TrafficDirection.Sent =>
        state.stats.copy(sentMessages = state.stats.sentMessages + 1, sentBytes = state.stats.sentBytes + bytes)
      case TrafficDirection.Received =>
        state.stats.copy(
          receivedMessages = state.stats.receivedMessages + 1,
          receivedBytes = state.stats.receivedBytes + bytes
        )
    state = state.copy(stats = stats)
    publish(SessionDiagnostic.Traffic(direction, bytes))

  /** A transport-level receive failure carries no frame bytes: classify the close code, then fail the session. */
  def transportFailed(error: ObsError): Unit = fail(classifyClose(error))

  /** One inbound frame in a single invocation: account its bytes, then process the decode outcome. Returns whether the
    * reader should keep consuming frames.
    */
  def received(bytes: Int, decoded: Either[ObsError, WireMessage]): Boolean =
    traffic(TrafficDirection.Received, bytes)
    decoded match
      case Right(message) => incoming(message)
      case Left(error)    =>
        fail(classifyClose(error))
        false

  /** obs-websocket close codes with a typed meaning are only legitimate before the session is ready: during Identify
    * the server rejects with 4009 (AuthenticationFailed), 4010 (UnsupportedRpcVersion — the client always requests RPC
    * version 1) or 4011 (SessionInvalidated). Once ready, the same codes keep their transport classification so retry
    * logic can still treat them as transient.
    */
  private def classifyClose(error: ObsError): ObsError = error match
    case ObsError.Transport(_, Some(code)) if state.phase != ConnectionState.Ready =>
      code match
        case 4009 => ObsError.Authentication("Server rejected authentication", Some(4009))
        case 4010 => ObsError.IncompatibleProtocol(1)
        case 4011 => ObsError.Authentication("Session invalidated by the server", Some(4011))
        case _    => error
    case other => other

  def requestFinished(requestType: String, id: String, elapsedNanos: Long, outcome: DiagnosticOutcome): Unit =
    val failed = if outcome == DiagnosticOutcome.Succeeded then 0L else 1L
    state = state.copy(stats =
      state.stats.copy(
        completedRequests = state.stats.completedRequests + 1,
        failedRequests = state.stats.failedRequests + failed,
        requestElapsedNanos = state.stats.requestElapsedNanos + elapsedNanos
      )
    )
    publish(SessionDiagnostic.RequestFinished(requestType, id, elapsedNanos, outcome))

  def subscribeDiagnostics(id: String, channel: Channel[SessionDiagnostic]): Either[ObsError, Unit] =
    if state.phase != ConnectionState.Ready then Left(state.failure.getOrElse(ObsError.Closed))
    else if state.diagnostics.entries.contains(id) then
      Left(ObsError.InternalError("Duplicate diagnostic subscription ID"))
    else
      state =
        state.copy(diagnostics = state.diagnostics.copy(entries = state.diagnostics.entries.updated(id, channel -> 0L)))
      Right(())

  def diagnosticLosses(id: String): Long = state.diagnostics.entries.get(id).map(_._2).getOrElse(0L)

  def unsubscribeDiagnostics(id: String, owner: Channel[SessionDiagnostic]): Unit =
    if state.diagnostics.entries.get(id).exists(_._1 eq owner) then
      owner.doneOrClosed().discard
      state = state.copy(diagnostics = state.diagnostics.copy(entries = state.diagnostics.entries - id))

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
    else if message.op == SessionWire.Op.Request || message.op == SessionWire.Op.RequestBatch then
      message.data.string("requestId").exists(state.pending.contains)
    else true

  /** A deterministic local send rejection completes only the affected request; the session stays alive. Messages
    * without a correlatable pending request fail the session, since Identify or Reidentify cannot be recovered.
    */
  def sendRejected(message: WireMessage, error: ObsError): Unit =
    val affected =
      if message.op == SessionWire.Op.Request || message.op == SessionWire.Op.RequestBatch then
        message.data.string("requestId").toOption
      else None
    affected match
      case Some(id) =>
        state.pending
          .get(id)
          .foreach: pending =>
            state = state.copy(pending = state.pending - id)
            pending.reply.trySend(Left(error)).discard
      case None =>
        if message.op != SessionWire.Op.Request && message.op != SessionWire.Op.RequestBatch then fail(error)

  def reidentify(mask: EventSubscriptions): Either[ObsError, Unit] =
    if state.phase != ConnectionState.Ready then Left(state.failure.getOrElse(ObsError.Closed))
    else if state.reidentifyAcks >= SessionLogic.maxReidentifyAcks then
      Left(ObsError.Overflow("reidentify acknowledgements"))
    else
      queue(
        WireMessage(
          SessionWire.Op.Reidentify,
          JsonObject(Map("eventSubscriptions" -> JsonValue.Num(BigDecimal(mask.value))))
        )
      ).map: _ =>
        state = state.copy(reidentifyAcks = state.reidentifyAcks + 1)

  def subscribe(
      id: String,
      channel: Channel[Event],
      types: Set[String],
      policy: OverflowPolicy,
      representation: EventRepresentation = EventRepresentation.Typed,
      dropped: AtomicLong = new AtomicLong(0L)
  ): Either[ObsError, Unit] =
    if state.phase != ConnectionState.Ready then Left(state.failure.getOrElse(ObsError.Closed))
    else if state.subscribers.contains(id) then Left(ObsError.InternalError("Duplicate subscription ID"))
    else
      state = state.copy(subscribers =
        state.subscribers.updated(id, Subscriber(channel, types, policy, dropped, representation))
      )
      Right(())

  def unsubscribe(id: String, owner: Channel[Event]): Unit =
    if state.subscribers.get(id).exists(_.channel eq owner) then
      owner.doneOrClosed().discard
      state = state.copy(subscribers = state.subscribers - id)

  /** Active registry observation only. A terminal subscription owns its own final counter. */
  def losses(id: String): Long = state.subscribers.get(id).map(_.dropped.get()).getOrElse(0L)

  /** Returns whether the reader should keep consuming frames. */
  def incoming(message: WireMessage): Boolean =
    val handled = (state.phase, message.op) match
      case (ConnectionState.AwaitingHello, SessionWire.Op.Hello)                          => hello(message.data)
      case (ConnectionState.Identifying, SessionWire.Op.Identified)                       => ready(message.data)
      case (ConnectionState.Ready, SessionWire.Op.Identified) if state.reidentifyAcks > 0 =>
        negotiatedRpc(message.data).map: _ =>
          state = state.copy(reidentifyAcks = state.reidentifyAcks - 1)
      case (ConnectionState.Ready, SessionWire.Op.Event) => event(message.data)
      case (ConnectionState.Ready, SessionWire.Op.RequestResponse | SessionWire.Op.RequestBatchResponse) =>
        response(message)
      case (ConnectionState.Failed | ConnectionState.Closed, _) => Right(())
      case _ => Left(ObsError.UnexpectedMessage(state.phase, message.op))
    handled.left.foreach(fail)
    state.phase != ConnectionState.Failed && state.phase != ConnectionState.Closed

  /** The password String cannot be wiped, but its byte copy and every downstream hash buffer can. */
  private def authenticationResponse(password: String, salt: String, challenge: String): String =
    val passwordBytes = password.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    try Authentication.compute(passwordBytes, salt, challenge)
    finally java.util.Arrays.fill(passwordBytes, 0.toByte)

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
          yield Map("authentication" -> JsonValue.Str(authenticationResponse(password, salt, challenge)))
        case _ => Left(ObsError.MalformedPayload("authentication", "Expected authentication object"))
      _ <- queue(
        WireMessage(
          SessionWire.Op.Identify,
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

  private def negotiatedRpc(data: JsonObject): Either[ObsError, Int] =
    data
      .int("negotiatedRpcVersion")
      .left
      .map(malformed)
      .flatMap: rpc =>
        if rpc == 1 then Right(rpc) else Left(ObsError.IncompatibleProtocol(rpc))

  private def ready(data: JsonObject): Either[ObsError, Unit] =
    negotiatedRpc(data).map: rpc =>
      transition(ConnectionState.Ready)
      identified.trySend(Right(ConnectionMetadata(state.version, rpc, Set.empty))).discard

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
              val result =
                if message.op == SessionWire.Op.RequestBatchResponse then Right(message.data)
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
    yield
      state = state.copy(stats = state.stats.copy(receivedEvents = state.stats.receivedEvents + 1))
      deliverEvent(eventType, eventData, decoded)

  private def deliverEvent(eventType: String, eventData: JsonObject, decoded: Event): Unit = state.subscribers.foreach:
    (id, subscriber) =>
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
                subscriber.dropped.incrementAndGet().discard
              case OverflowPolicy.DropOldest =>
                val dropped = SubscriptionDelivery.replaceOldest(
                  () => subscriber.channel.tryReceiveOrClosed(),
                  () => subscriber.channel.trySendOrClosed(delivered)
                )
                subscriber.dropped.addAndGet(dropped).discard
          case _ => state = state.copy(subscribers = state.subscribers - id)

private[client] object SessionLogic:
  /** Reidentify acknowledgements are uncorrelated server messages, so their backlog is bounded by a dedicated constant
    * rather than the in-flight request capacity.
    */
  val maxReidentifyAcks: Int = 16
