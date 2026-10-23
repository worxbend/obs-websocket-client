package com.worxbend.obs.websocket.client

import com.worxbend.obs.websocket.client.protocol.*
import com.worxbend.obs.websocket.client.SessionActor.askOrClosed
import ox.channels.{ActorRef, Channel, ChannelClosedException}
import ox.{discard, sleep, timeoutOption, uninterruptible}
import ox.either.catching
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
  ids:          CorrelationIds = new CorrelationIds,
  /** Immutable raw GetVersion discovery for this generation; unavailable only to the internal provisional facade. */
  val discovery: Option[JsonObject] = None,
) extends RequestApi[ObsError]:
  private val exchanges = new RequestExchange(config = config, dependencies = dependencies, logic = logic, ids = ids)
  private def ask[A](f: SessionLogic => Either[ObsError, A]): Either[ObsError, A] = logic.askOrClosed(f)

  /** Best-effort bookkeeping tell; see [[SessionActor.tellSafely]]. */
  private[client] def tellSafely(f: SessionLogic => Unit): Unit = SessionActor.tellSafely(logic)(f)

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
    PreparedRequest.from(request = request, available = metadata.availableRequests)
      .flatMap(prepared => executePrepared(request = request, prepared = prepared, options = options))

  private def executePrepared[A](
    request:  Request[A],
    prepared: PreparedRequest,
    options:  RequestOptions,
  ): Either[ObsError, ResponseEnvelope[A]] =
    rawRequest(requestType = prepared.requestType, data = prepared.data, options = options).map: raw =>
      ResponseEnvelope(raw = raw, decoded = request.decodeResponse(data = raw).left.map(SessionWire.malformed))

  /** Retry only explicit NotReady (207), only for the reviewed read catalog, within one total budget. */
  def requestWhenReady[A](
    request: Request[A],
    policy:  ReadinessPolicy = ReadinessPolicy(),
    options: RequestOptions = RequestOptions(),
  ): Either[ObsError, A] =
    request match
      case _: RawRequest => Left(ReadinessPolicy.unsupportedRequest)
      case _             =>
        for
          prepared <- PreparedRequest.from(request = request, available = metadata.availableRequests)
          _        <-
            if ReadinessPolicy.supportedRequests.contains(prepared.requestType) then Right(())
            else Left(ReadinessPolicy.unsupportedRequest)
          _      <- policy.validate
          budget <- options.deadline(default = config.requestTimeout)
          result <- timeoutOption(budget)(
                      retryReady(policy = policy)(run =
                        executePrepared(
                          request  = request,
                          prepared = prepared,
                          options  = options,
                        ).flatMap(_.decoded)
                      )
                    )
                      .getOrElse(Left(ObsError.Timeout(operation = prepared.requestType)))
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

  /** Escape hatch for vendor extensions and requests newer than the pinned catalog. A returned [[ObsError.Timeout]]
    * after the request reached the wire is ambiguous: the server may already have executed a mutation.
    */
  def rawRequest(
    requestType: String,
    data:        JsonObject = JsonObject.empty,
    options:     RequestOptions = RequestOptions(),
  ): Either[ObsError, JsonObject] =
    exchanges.run(requestType = requestType, responseOpcode = SessionWire.Op.RequestResponse, options = options): id =>
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
    // Seeds are not wire IDs: a repeated seed must still yield a unique subscription key.
    ids.next(seed = dependencies.nextRequestId()).flatMap: id =>
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
      val prepared = requests.foldLeft[Either[ObsError, Vector[PreparedRequest]]](Right(Vector.empty))((acc, request) =>
        acc.flatMap(values =>
          PreparedRequest.from(request = request, available = metadata.availableRequests).map(values :+ _)
        )
      )
      prepared.flatMap: snapshots =>
        // Known admission rejection is unambiguous. Canceled admission acknowledgements can race queued work,
        // so the exchange conservatively wraps failures whenever admission may already have happened.
        val response =
          exchanges.run(
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
                      value = snapshots.map(_.batchEntry)
                    ),
                  )
              ),
            )
        response.flatMap(BatchWire.decode(_, requests = snapshots, policy = failurePolicy))

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

  /** Session lifetime counters include identification and capability discovery; bytes are logical UTF-8 JSON. */
  def statistics: Either[ObsError, SessionStats] = ask(s => Right(s.statistics))

  /** Scoped stream of session diagnostics (traffic, request outcomes, state changes), bounded at `capacity`; a slow
    * consumer drops records.
    */
  def withDiagnostics[A](capacity: Int = 128)(use: ObsDiagnosticsSubscription => A): Either[ObsError, A] =
    if capacity <= 0 then Left(ObsError.InvalidConfiguration(message = "Diagnostic capacity must be positive"))
    else
      ids.next(seed = dependencies.nextRequestId()).flatMap: id =>
        val channel = Channel.buffered[SessionDiagnostic](capacity)
        val dropped = new AtomicLong(0L)
        try
          ask(f = _.subscribeDiagnostics(id = id, channel = channel, dropped = dropped)).map: _ =>
            use(new ObsDiagnosticsSubscription(channel = channel, () => dropped.get()))
        finally uninterruptible(tellSafely(f = _.unsubscribeDiagnostics(id = id, owner = channel)))

  /** The current connection phase, for observing lifecycle transitions without subscribing to diagnostics. */
  def state: Either[ObsError, ConnectionState] = ask(s => Right(s.phase))

  /** Completes all outstanding requests and subscriptions immediately. Uninterruptible so a close in flight cannot be
    * silently lost when the caller is interrupted.
    */
  def close(): Unit = uninterruptible(logic.ask(_.close()).catching[ChannelClosedException].discard)
