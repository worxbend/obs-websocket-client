package com.worxbend.obs.websocket.client

import com.worxbend.obs.websocket.client.protocol.*
import ox.channels.{ActorRef, Channel, ChannelClosedException}
import ox.{discard, sleep, timeoutOption, uninterruptible}
import ox.either.catching
import java.util.concurrent.atomic.AtomicLong

/** A session belongs to the callback passed to ObsClient.withTransport. */
final class ObsSession private[client] (
    val metadata: ConnectionMetadata,
    config: ObsConfig,
    dependencies: SessionDependencies,
    logic: ActorRef[SessionLogic]
) extends RequestApi[ObsError]:
  private def ask[A](f: SessionLogic => Either[ObsError, A]): Either[ObsError, A] =
    logic.ask(f).catching[ChannelClosedException].left.map(_ => ObsError.Closed).flatten

  /** Typed catalog request. A request type absent from the discovered capability set is rejected locally with
    * [[ObsError.UnsupportedRequest]]; an empty capability set therefore rejects every typed request. `RawRequest`
    * bypasses this check so vendor extensions and requests newer than the pinned catalog can reach the server.
    */
  def request[A](request: Request[A]): Either[ObsError, A] = requestEnvelope(request).flatMap(_.decoded)

  def request[A](request: Request[A], options: RequestOptions): Either[ObsError, A] =
    requestEnvelope(request, options).flatMap(_.decoded)

  /** Domain facades sharing one per-operation budget configuration. */
  def withOptions(options: RequestOptions): RequestApi[ObsError] = new RequestApi[ObsError]:
    def request[A](value: Request[A]): Either[ObsError, A] = ObsSession.this.request(value, options)

  def requestEnvelope[A](
      request: Request[A],
      options: RequestOptions = RequestOptions()
  ): Either[ObsError, ResponseEnvelope[A]] =
    if !capable(request) then Left(ObsError.UnsupportedRequest(request.requestType))
    else
      request.validate.left
        .map(SessionWire.invalid)
        .flatMap: _ =>
          rawRequest(request.requestType, request.requestData, options).map: raw =>
            ResponseEnvelope(raw, request.decodeResponse(raw).left.map(SessionWire.malformed))

  /** Retry only explicit NotReady (207), only for the reviewed read catalog, within one total budget. */
  def requestWhenReady[A](
      request: Request[A],
      policy: ReadinessPolicy = ReadinessPolicy(),
      options: RequestOptions = RequestOptions()
  ): Either[ObsError, A] =
    request match
      case _: RawRequest =>
        Left(ObsError.InvalidConfiguration("Readiness retries require a reviewed typed read request"))
      case _ if !ReadinessPolicy.supportedRequests.contains(request.requestType) =>
        Left(ObsError.InvalidConfiguration("Readiness retries require a reviewed typed read request"))
      case _ =>
        for
          _ <- policy.validate
          budget <- options.deadline(config.requestTimeout)
          result <- timeoutOption(budget)(retryReady(policy)(this.request(request, options)))
            .getOrElse(Left(ObsError.Timeout(request.requestType)))
        yield result

  private[client] def discoverVersion: Either[ObsError, JsonObject] = config.readiness match
    case None         => rawRequest("GetVersion")
    case Some(policy) =>
      timeoutOption(config.requestTimeout)(retryReady(policy)(rawRequest("GetVersion")))
        .getOrElse(Left(ObsError.Timeout("GetVersion")))

  private def retryReady[A](policy: ReadinessPolicy)(run: => Either[ObsError, A]): Either[ObsError, A] =
    var result = run
    var attempts = 1
    while attempts < policy.maxAttempts && (result match
        case Left(ObsError.RequestRejected(_, _, 207, _)) => true
        case _                                            => false)
    do
      sleep(policy.delay)
      result = run
      attempts += 1
    result

  private def capable(request: Request[?]): Boolean = request match
    case _: RawRequest => true
    case _             => metadata.availableRequests.contains(request.requestType)

  /** Escape hatch for vendor extensions and requests newer than the pinned catalog. */
  def rawRequest(
      requestType: String,
      data: JsonObject = JsonObject.empty,
      options: RequestOptions = RequestOptions()
  ): Either[ObsError, JsonObject] =
    exchange(requestType, SessionWire.Op.RequestResponse, options): id =>
      WireMessage(
        SessionWire.Op.Request,
        JsonObject(
          Map(
            "requestType" -> JsonValue.Str(requestType),
            "requestId" -> JsonValue.Str(id),
            "requestData" -> data
          )
        )
      )

  private def exchange(requestType: String, responseOpcode: Int, options: RequestOptions)(
      message: String => WireMessage
  ): Either[ObsError, JsonObject] = exchange(requestType, responseOpcode, options, identity)(message)

  /** `postRegistration` wraps only failures arising after the message was registered and queued: before that point
    * nothing reached the writer, so the error is returned unwrapped.
    */
  private def exchange(
      requestType: String,
      responseOpcode: Int,
      options: RequestOptions,
      postRegistration: ObsError => ObsError
  )(
      message: String => WireMessage
  ): Either[ObsError, JsonObject] =
    options
      .deadline(config.requestTimeout)
      .flatMap: budget =>
        val id = dependencies.nextRequestId()
        val reply = Channel.buffered[Either[ObsError, JsonObject]](1)
        val started = dependencies.nanoTime()
        var outcome: DiagnosticOutcome = DiagnosticOutcome.Cancelled
        try
          ask(_.register(id, requestType, responseOpcode, message(id), reply)).flatMap: _ =>
            val completed = timeoutOption(budget)(reply.receive()).getOrElse:
              // A response landing between timeout expiry and this final check is delivered, not reported as a timeout.
              reply.tryReceive().getOrElse(Left(ObsError.Timeout(requestType)))
            outcome = DiagnosticOutcome.from(completed)
            completed.left.map:
              case error @ (ObsError.Transport(_, _) | ObsError.Timeout(_) | ObsError.Closed) =>
                postRegistration(error)
              case error => error
        finally
          uninterruptible:
            val elapsed = dependencies.nanoTime() - started
            logic
              .tell(state =>
                state.cancel(id, reply)
                state.requestFinished(requestType, id, elapsed, outcome)
              )
              .catching[ChannelClosedException]
              .discard

  /** Queue a server subscription change. Success means queued; the uncorrelated Identified acknowledgement is validated
    * asynchronously while the session remains ready.
    */
  def reidentify(subscriptions: EventSubscriptions): Either[ObsError, Unit] = ask(_.reidentify(subscriptions))

  /** Observation hook for tests: reidentify acknowledgements the server has not sent yet. Not public API. */
  private[client] def pendingReidentifyAcks: Either[ObsError, Int] = ask(s => Right(s.pendingReidentifyAcks))

  /** Each subscriber receives future matching events independently. User code runs on its caller. */
  def withEvents[A](eventTypes: Set[String] = Set.empty, policy: OverflowPolicy = OverflowPolicy.Fail)(
      use: ObsSubscription => A
  ): Either[ObsError, A] =
    subscribe(eventTypes, policy, EventRepresentation.Typed)(use)

  def withEvents[E <: Event, A](selector: EventSelector[E])(use: TypedObsSubscription[E] => A): Either[ObsError, A] =
    withEvents(selector, OverflowPolicy.Fail)(use)

  def withEvents[E <: Event, A](selector: EventSelector[E], policy: OverflowPolicy)(
      use: TypedObsSubscription[E] => A
  ): Either[ObsError, A] =
    subscribe(Set(selector.eventType), policy, EventRepresentation.Typed)(source =>
      use(new TypedObsSubscription(source, selector))
    )

  /** Preserve all event payload fields, including unknown additions to a known event type. Events arrive as
    * `UnknownEvent`, the same representation unknown event types receive under [[withEvents]].
    */
  def withRawEvents[A](eventTypes: Set[String] = Set.empty, policy: OverflowPolicy = OverflowPolicy.Fail)(
      use: ObsSubscription => A
  ): Either[ObsError, A] =
    subscribe(eventTypes, policy, EventRepresentation.Raw)(use)

  private def subscribe[A](eventTypes: Set[String], policy: OverflowPolicy, representation: EventRepresentation)(
      use: ObsSubscription => A
  ): Either[ObsError, A] =
    val id = dependencies.nextRequestId()
    val channel = Channel.buffered[Event](config.subscriptionCapacity)
    val dropped = new AtomicLong(0L)
    try
      ask(_.subscribe(id, channel, eventTypes, policy, representation, dropped)).map: _ =>
        use(
          new ObsSubscription(
            channel,
            () => dropped.get(),
            dependencies.nanoTime
          )
        )
    finally uninterruptible(logic.tell(_.unsubscribe(id, channel)).catching[ChannelClosedException].discard)

  /** Execute heterogeneous requests, preserving raw response data and each submitted position. Typed entries are
    * capability-checked like [[request]]; `RawRequest` entries bypass that check.
    */
  def batch(
      requests: Vector[Request[?]],
      execution: BatchExecution = BatchExecution.SerialRealtime,
      failurePolicy: BatchFailurePolicy = BatchFailurePolicy.Continue,
      options: RequestOptions = RequestOptions()
  ): Either[ObsError, Vector[BatchResult]] =
    if requests.isEmpty then Right(Vector.empty)
    else if execution == BatchExecution.Parallel then
      Left(ObsError.InvalidConfiguration("Parallel batches cannot safely correlate results on supported OBS servers"))
    else
      val validated = requests.foldLeft[Either[ObsError, Unit]](Right(()))((acc, request) =>
        acc.flatMap: _ =>
          if !capable(request) then Left(ObsError.UnsupportedRequest(request.requestType))
          else request.validate.left.map(SessionWire.invalid)
      )
      validated.flatMap: _ =>
        // Only failures after successful registration wrap as ambiguous: before registration nothing was queued,
        // so the batch definitely never executed.
        val response =
          exchange("RequestBatch", SessionWire.Op.RequestBatchResponse, options, ObsError.AmbiguousBatchOutcome(_)):
            id =>
              WireMessage(
                SessionWire.Op.RequestBatch,
                JsonObject(
                  Map(
                    "requestId" -> JsonValue.Str(id),
                    "haltOnFailure" -> JsonValue.Bool(failurePolicy == BatchFailurePolicy.Halt),
                    "executionType" -> JsonValue.Num(BigDecimal(execution.wireValue)),
                    "requests" -> JsonValue.Arr(
                      requests.map(request =>
                        JsonObject(
                          Map(
                            "requestType" -> JsonValue.Str(request.requestType),
                            "requestData" -> request.requestData
                          )
                        )
                      )
                    )
                  )
                )
              )
        response.flatMap(decodeBatch(_, requests, failurePolicy))

  /** Example: typedBatch((BatchCall(GetVersion()), BatchCall(GetSceneList()))). */
  def typedBatch[Calls <: Tuple](
      calls: Calls,
      execution: BatchExecution = BatchExecution.SerialRealtime,
      failurePolicy: BatchFailurePolicy = BatchFailurePolicy.Continue,
      options: RequestOptions = RequestOptions()
  )(using codec: BatchCodec[Calls]): Either[ObsError, BatchResults[Calls]] =
    batch(codec.requests(calls), execution, failurePolicy, options).map(codec.decode(calls, _))

  private def decodeBatch(
      data: JsonObject,
      requests: Vector[Request[?]],
      policy: BatchFailurePolicy
  ): Either[ObsError, Vector[BatchResult]] =
    data
      .array("results")
      .left
      .map(SessionWire.malformed)
      .flatMap: results =>
        if results.size > requests.size || (policy == BatchFailurePolicy.Continue && results.size != requests.size) then
          Left(ObsError.MalformedPayload("results", "Batch result count does not match submitted requests"))
        else
          val decoded = requests.zipWithIndex.foldLeft[Either[ObsError, Vector[BatchResult]]](Right(Vector.empty)):
            case (acc, (request, index)) =>
              acc.flatMap: values =>
                results.lift(index) match
                  case None                     => Right(values :+ BatchResult.NotExecuted(request.requestType))
                  case Some(result: JsonObject) =>
                    SessionWire.responseData(result, request.requestType, s"batch:$index") match
                      case Left(error: ObsError.MalformedPayload) => Left(error)
                      case response => Right(values :+ BatchResult.Completed(request.requestType, response))
                  case _ => Left(ObsError.MalformedPayload(s"results[$index]", "Expected object"))
          decoded.flatMap: values =>
            val firstFailure = values.indexWhere:
              case BatchResult.Completed(_, Left(_)) => true
              case _                                 => false
            if policy == BatchFailurePolicy.Halt && !haltSequenceValid(firstFailure, results.size, requests.size)
            then Left(ObsError.MalformedPayload("results", "Halted batch must end immediately after a failure"))
            else Right(values)

  /** A halted batch is coherent only when every request executed without a failure, or the first failure is the last
    * returned result and nothing follows it.
    */
  private def haltSequenceValid(firstFailure: Int, resultCount: Int, requestCount: Int): Boolean =
    (firstFailure >= 0 && firstFailure == resultCount - 1) || (firstFailure < 0 && resultCount == requestCount)

  /** Session lifetime counters include identification and capability discovery; bytes are logical UTF-8 JSON. */
  def statistics: Either[ObsError, SessionStats] = ask(s => Right(s.statistics))

  def withDiagnostics[A](capacity: Int = 128)(use: ObsDiagnosticsSubscription => A): Either[ObsError, A] =
    if capacity <= 0 then Left(ObsError.InvalidConfiguration("Diagnostic capacity must be positive"))
    else
      val id = dependencies.nextRequestId()
      val channel = Channel.buffered[SessionDiagnostic](capacity)
      try
        ask(_.subscribeDiagnostics(id, channel)).map: _ =>
          use(
            new ObsDiagnosticsSubscription(
              channel,
              () => logic.ask(_.diagnosticLosses(id)).catching[ChannelClosedException].getOrElse(0L)
            )
          )
      finally
        uninterruptible(logic.tell(_.unsubscribeDiagnostics(id, channel)).catching[ChannelClosedException].discard)

  def state: Either[ObsError, ConnectionState] = ask(s => Right(s.phase))

  /** Completes all outstanding requests and subscriptions immediately. */
  def close(): Unit = logic.ask(_.close()).catching[ChannelClosedException].discard
