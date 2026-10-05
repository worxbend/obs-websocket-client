package com.worxbend.obs.websocket.client

import com.worxbend.obs.websocket.client.protocol.*
import ox.channels.{ActorRef, Channel, ChannelClosedException}
import ox.{discard, sleep, timeoutOption, uninterruptible}
import ox.either.catching
import scala.util.control.NonFatal
import java.util.concurrent.atomic.AtomicLong

/** A live, identified connection to one OBS server. The session belongs to the `ObsClient.withTransport` callback and
  * is closed when that scope exits; do not leak it. All methods are thread-safe — calls serialize through the session
  * actor — and report expected failures as `Left(ObsError)` rather than throwing.
  */
final class ObsSession private[client] (
  val metadata: ConnectionMetadata,
  config:       ObsConfig,
  dependencies: SessionDependencies,
  logic:        ActorRef[SessionLogic],
) extends RequestApi[ObsError]:
  private def ask[A](f: SessionLogic => Either[ObsError, A]): Either[ObsError, A] =
    logic.ask(f).catching[ChannelClosedException].left.map(_ => ObsError.Closed).flatten

  /** Bookkeeping tells are best-effort: a defect in cleanup accounting must not kill the actor and take the whole
    * session scope down with a raw escape.
    */
  private[client] def tellSafely(f: SessionLogic => Unit): Unit =
    logic
      .tell(state =>
        try f(state)
        catch case NonFatal(_) => ()
      )
      .catching[ChannelClosedException]
      .discard

  /** Typed catalog request. A request type absent from the discovered capability set is rejected locally with
    * [[ObsError.UnsupportedRequest]]; an empty capability set therefore rejects every typed request. `RawRequest`
    * bypasses this check so vendor extensions and requests newer than the pinned catalog can reach the server.
    *
    * A returned [[ObsError.Timeout]] is ambiguous once the request reached the wire: the server may already have
    * executed a mutation. Batches surface this explicitly as [[ObsError.AmbiguousBatchOutcome]]; single requests return
    * the bare timeout.
    */
  def request[A](request: Request[A]): Either[ObsError, A] = requestEnvelope(request = request).flatMap(_.decoded)

  /** As [[request]], with a per-call [[RequestOptions]] override (e.g. a custom timeout budget). */
  def request[A](request: Request[A], options: RequestOptions): Either[ObsError, A] =
    requestEnvelope(request = request, options = options).flatMap(_.decoded)

  /** Domain facades sharing one per-operation budget configuration. */
  def withOptions(options: RequestOptions): RequestApi[ObsError] = new RequestApi[ObsError]:
    def request[A](value: Request[A]): Either[ObsError, A] = ObsSession.this.request(request = value, options = options)

  /** As [[request]], but returns a [[ResponseEnvelope]] retaining the raw response JSON alongside the decoded value,
    * so callers can inspect fields the typed catalog does not model.
    */
  def requestEnvelope[A](
    request: Request[A],
    options: RequestOptions = RequestOptions(),
  ): Either[ObsError, ResponseEnvelope[A]] =
    if !capable(request = request) then Left(ObsError.UnsupportedRequest(requestType = request.requestType))
    else
      request.validate.left
        .map(SessionWire.invalid)
        .flatMap: _ =>
          rawRequest(requestType = request.requestType, data = request.requestData, options = options).map: raw =>
            ResponseEnvelope(raw = raw, decoded = request.decodeResponse(data = raw).left.map(SessionWire.malformed))

  /** Retry only explicit NotReady (207), only for the reviewed read catalog, within one total budget. */
  def requestWhenReady[A](
    request: Request[A],
    policy:  ReadinessPolicy = ReadinessPolicy(),
    options: RequestOptions = RequestOptions(),
  ): Either[ObsError, A] =
    request match
      case _: RawRequest =>
        Left(ObsError.InvalidConfiguration(message = "Readiness retries require a reviewed typed read request"))
      case _ if !ReadinessPolicy.supportedRequests.contains(request.requestType) =>
        Left(ObsError.InvalidConfiguration(message = "Readiness retries require a reviewed typed read request"))
      case _ =>
        for
          _      <- policy.validate
          budget <- options.deadline(default = config.requestTimeout)
          result <- timeoutOption(budget)(
                      retryReady(policy = policy)(run = this.request(request = request, options = options))
                    )
                      .getOrElse(Left(ObsError.Timeout(operation = request.requestType)))
        yield result

  private[client] def discoverVersion: Either[ObsError, JsonObject] = config.readiness match
    case None         => rawRequest(requestType = "GetVersion")
    case Some(policy) =>
      timeoutOption(config.requestTimeout)(retryReady(policy = policy)(run = rawRequest(requestType = "GetVersion")))
        .getOrElse(Left(ObsError.Timeout(operation = "GetVersion")))

  private def retryReady[A](policy: ReadinessPolicy)(run: => Either[ObsError, A]): Either[ObsError, A] =
    var result   = run
    var attempts = 1
    while attempts < policy.maxAttempts &&
      (result match
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

  /** Escape hatch for vendor extensions and requests newer than the pinned catalog. A returned [[ObsError.Timeout]]
    * after the request reached the wire is ambiguous: the server may already have executed a mutation.
    */
  def rawRequest(
    requestType: String,
    data:        JsonObject = JsonObject.empty,
    options:     RequestOptions = RequestOptions(),
  ): Either[ObsError, JsonObject] =
    exchange(requestType = requestType, responseOpcode = SessionWire.Op.RequestResponse, options = options): id =>
      WireMessage(
        op   = SessionWire.Op.Request,
        data = JsonObject(
          fields = Map(
            "requestType" -> JsonValue.Str(value = requestType),
            "requestId"   -> JsonValue.Str(value = id),
            "requestData" -> data,
          )
        ),
      )

  private def exchange(requestType: String, responseOpcode: Int, options: RequestOptions)(
    message: String => WireMessage
  ): Either[ObsError, JsonObject] = exchange(
    requestType      = requestType,
    responseOpcode   = responseOpcode,
    options          = options,
    postRegistration = identity,
  )(message = message)

  /** `postRegistration` wraps only failures arising after the message was registered and queued: before that point
    * nothing reached the writer, so the error is returned unwrapped.
    */
  private def exchange(
    requestType:      String,
    responseOpcode:   Int,
    options:          RequestOptions,
    postRegistration: ObsError => ObsError,
  )(
    message: String => WireMessage
  ): Either[ObsError, JsonObject] =
    options
      .deadline(default = config.requestTimeout)
      .flatMap: budget =>
        val id                         = dependencies.nextRequestId()
        val reply                      = Channel.buffered[Either[ObsError, JsonObject]](1)
        val started                    = dependencies.nanoTime()
        var outcome: DiagnosticOutcome = DiagnosticOutcome.Cancelled
        val registered                 =
          try
            ask(f =
              _.register(
                id          = id,
                requestType = requestType,
                opcode      = responseOpcode,
                message     = message(id),
                reply       = reply,
              )
            )
          catch
            case interrupted: InterruptedException =>
              // The register invocation may already be queued on the actor and execute after the caller is
              // gone; cancel unconditionally (a no-op if it never landed) so interruption cannot orphan a
              // pending entry whose request would be sent with no one awaiting it.
              uninterruptible(tellSafely(f = _.cancel(id = id, owner = reply)))
              throw interrupted
        try
          registered.flatMap: _ =>
            val completed = timeoutOption(budget)(reply.receive()).getOrElse:
              // A response landing between timeout expiry and this final check is delivered, not reported as a timeout.
              reply.tryReceive().getOrElse(Left(ObsError.Timeout(operation = requestType)))
            outcome = DiagnosticOutcome.from(result = completed)
            completed.left.map:
              case error @ (ObsError.Transport(_, _) | ObsError.Timeout(_) | ObsError.Closed) =>
                postRegistration(error)
              case error => error
        finally
          // Only a registered request accounts a finished one: a rejection at registration never
          // reached the writer and must not move the completed/failed counters.
          if registered.isRight then
            uninterruptible:
              val elapsed = dependencies.nanoTime() - started
              tellSafely: state =>
                state.cancel(id                   = id, owner       = reply)
                state.requestFinished(requestType = requestType, id = id, elapsedNanos = elapsed, outcome = outcome)

  /** Queue a server subscription change. Success means queued; the uncorrelated Identified acknowledgement is validated
    * asynchronously while the session remains ready.
    */
  def reidentify(subscriptions: EventSubscriptions): Either[ObsError, Unit] =
    ask(f = _.reidentify(mask = subscriptions))

  /** Observation hook for tests: reidentify acknowledgements the server has not sent yet. Not public API. */
  private[client] def pendingReidentifyAcks: Either[ObsError, Int] = ask(s => Right(s.pendingReidentifyAcks))

  /** Each subscriber receives future matching events independently. The callback and all event consumption run on the
    * calling thread.
    */
  def withEvents[A](eventTypes: Set[String] = Set.empty, policy: OverflowPolicy = OverflowPolicy.Fail)(
    use: ObsSubscription => A
  ): Either[ObsError, A] =
    subscribe(eventTypes = eventTypes, policy = policy, representation = EventRepresentation.Typed)(use = use)

  /** Typed view of one event type selected by `selector`; uses the `Fail` overflow policy. */
  def withEvents[E <: Event, A](selector: EventSelector[E])(use: TypedObsSubscription[E] => A): Either[ObsError, A] =
    withEvents(selector = selector, policy = OverflowPolicy.Fail)(use = use)

  /** As above, with an explicit overflow policy for the shared event queue. */
  def withEvents[E <: Event, A](selector: EventSelector[E], policy: OverflowPolicy)(
    use: TypedObsSubscription[E] => A
  ): Either[ObsError, A] =
    subscribe(eventTypes = Set(selector.eventType), policy = policy, representation = EventRepresentation.Typed)(
      source => use(new TypedObsSubscription(source, selector))
    )

  /** Preserve all event payload fields, including unknown additions to a known event type. Events arrive as
    * `UnknownEvent`, the same representation unknown event types receive under [[withEvents]].
    */
  def withRawEvents[A](eventTypes: Set[String] = Set.empty, policy: OverflowPolicy = OverflowPolicy.Fail)(
    use: ObsSubscription => A
  ): Either[ObsError, A] =
    subscribe(eventTypes = eventTypes, policy = policy, representation = EventRepresentation.Raw)(use = use)

  private def subscribe[A](eventTypes: Set[String], policy: OverflowPolicy, representation: EventRepresentation)(
    use: ObsSubscription => A
  ): Either[ObsError, A] =
    val id      = dependencies.nextRequestId()
    val channel = Channel.buffered[Event](config.subscriptionCapacity)
    val dropped = new AtomicLong(0L)
    try
      ask(f =
        _.subscribe(
          id             = id,
          channel        = channel,
          types          = eventTypes,
          policy         = policy,
          representation = representation,
          dropped        = dropped,
        )
      ).map: _ =>
        use(
          new ObsSubscription(
            channel = channel,
            () => dropped.get(),
            nanoTime = dependencies.nanoTime,
          )
        )
    finally uninterruptible(tellSafely(f = _.unsubscribe(id = id, owner = channel)))

  /** Execute heterogeneous requests, preserving raw response data and each submitted position. Typed entries are
    * capability-checked like [[request]]; `RawRequest` entries bypass that check.
    */
  def batch(
    requests:      Vector[Request[?]],
    execution:     BatchExecution = BatchExecution.SerialRealtime,
    failurePolicy: BatchFailurePolicy = BatchFailurePolicy.Continue,
    options:       RequestOptions = RequestOptions(),
  ): Either[ObsError, Vector[BatchResult]] =
    if requests.isEmpty then Right(Vector.empty)
    else if execution == BatchExecution.Parallel then
      Left(
        ObsError.InvalidConfiguration(message =
          "Parallel batches cannot safely correlate results on supported OBS servers"
        )
      )
    else
      val validated = requests.foldLeft[Either[ObsError, Unit]](Right(()))((acc, request) =>
        acc.flatMap: _ =>
          if !capable(request = request) then Left(ObsError.UnsupportedRequest(requestType = request.requestType))
          else request.validate.left.map(SessionWire.invalid)
      )
      validated.flatMap: _ =>
        // Only failures after successful registration wrap as ambiguous: before registration nothing was queued,
        // so the batch definitely never executed.
        val response =
          exchange(
            requestType      = "RequestBatch",
            responseOpcode   = SessionWire.Op.RequestBatchResponse,
            options          = options,
            postRegistration = ObsError.AmbiguousBatchOutcome(_),
          ): id =>
            WireMessage(
              op   = SessionWire.Op.RequestBatch,
              data = JsonObject(
                fields =
                  Map(
                    "requestId"     -> JsonValue.Str(value = id),
                    "haltOnFailure" -> JsonValue.Bool(value = failurePolicy == BatchFailurePolicy.Halt),
                    "executionType" -> JsonValue.Num(value = BigDecimal(execution.wireValue)),
                    "requests"      -> JsonValue.Arr(
                      value = requests.map(request =>
                        JsonObject(
                          fields = Map(
                            "requestType" -> JsonValue.Str(value = request.requestType),
                            "requestData" -> request.requestData,
                          )
                        )
                      )
                    ),
                  )
              ),
            )
        response.flatMap(decodeBatch(_, requests = requests, policy = failurePolicy))

  /** Example: typedBatch((BatchCall(GetVersion()), BatchCall(GetSceneList()))). */
  def typedBatch[Calls <: Tuple](
    calls:         Calls,
    execution:     BatchExecution = BatchExecution.SerialRealtime,
    failurePolicy: BatchFailurePolicy = BatchFailurePolicy.Continue,
    options:       RequestOptions = RequestOptions(),
  )(using codec: BatchCodec[Calls]): Either[ObsError, BatchResults[Calls]] =
    batch(
      requests      = codec.requests(calls = calls),
      execution     = execution,
      failurePolicy = failurePolicy,
      options       = options,
    ).map(codec.decode(calls = calls, _))

  private def decodeBatch(
    data:     JsonObject,
    requests: Vector[Request[?]],
    policy:   BatchFailurePolicy,
  ): Either[ObsError, Vector[BatchResult]] =
    data
      .array(name = "results")
      .left
      .map(SessionWire.malformed)
      .flatMap: results =>
        if results.size > requests.size || (policy == BatchFailurePolicy.Continue && results.size != requests.size) then
          Left(
            ObsError
              .MalformedPayload(path = "results", message = "Batch result count does not match submitted requests")
          )
        else
          val decoded = requests.zipWithIndex.foldLeft[Either[ObsError, Vector[BatchResult]]](Right(Vector.empty)):
            case (acc, (request, index)) =>
              acc.flatMap: values =>
                results.lift(index) match
                  case None => Right(values :+ BatchResult.NotExecuted(requestType = request.requestType))
                  case Some(result: JsonObject) =>
                    SessionWire.responseData(
                      data         = result,
                      expectedType = request.requestType,
                      id           = s"batch:$index",
                    ) match
                      case Left(error: ObsError.MalformedPayload) => Left(error)
                      case response                               =>
                        Right(values :+ BatchResult.Completed(requestType = request.requestType, result = response))
                  case _ => Left(ObsError.MalformedPayload(path = s"results[$index]", message = "Expected object"))
          decoded.flatMap: values =>
            val firstFailure = values.indexWhere:
              case BatchResult.Completed(_, Left(_)) => true
              case _                                 => false
            if policy == BatchFailurePolicy.Halt && !haltSequenceValid(
                firstFailure = firstFailure,
                resultCount  = results.size,
                requestCount = requests.size,
              )
            then
              Left(
                ObsError
                  .MalformedPayload(path = "results", message = "Halted batch must end immediately after a failure")
              )
            else Right(values)

  /** A halted batch is coherent only when every request executed without a failure, or the first failure is the last
    * returned result and nothing follows it.
    */
  private def haltSequenceValid(firstFailure: Int, resultCount: Int, requestCount: Int): Boolean =
    (firstFailure >= 0 && firstFailure == resultCount - 1) || (firstFailure < 0 && resultCount == requestCount)

  /** Session lifetime counters include identification and capability discovery; bytes are logical UTF-8 JSON. */
  def statistics: Either[ObsError, SessionStats] = ask(s => Right(s.statistics))

  /** Scoped stream of session diagnostics (traffic, request outcomes, state changes), bounded at `capacity`; a slow
    * consumer drops records.
    */
  def withDiagnostics[A](capacity: Int = 128)(use: ObsDiagnosticsSubscription => A): Either[ObsError, A] =
    if capacity <= 0 then Left(ObsError.InvalidConfiguration(message = "Diagnostic capacity must be positive"))
    else
      val id      = dependencies.nextRequestId()
      val channel = Channel.buffered[SessionDiagnostic](capacity)
      try
        ask(f = _.subscribeDiagnostics(id = id, channel = channel)).map: _ =>
          use(
            new ObsDiagnosticsSubscription(
              channel = channel,
              () => logic.ask(_.diagnosticLosses(id = id)).catching[ChannelClosedException].getOrElse(0L),
            )
          )
      finally uninterruptible(tellSafely(f = _.unsubscribeDiagnostics(id = id, owner = channel)))

  /** The current connection phase, for observing lifecycle transitions without subscribing to diagnostics. */
  def state: Either[ObsError, ConnectionState] = ask(s => Right(s.phase))

  /** Completes all outstanding requests and subscriptions immediately. Uninterruptible so a close in flight cannot be
    * silently lost when the caller is interrupted.
    */
  def close(): Unit = uninterruptible(logic.ask(_.close()).catching[ChannelClosedException].discard)
