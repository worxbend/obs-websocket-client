package com.worxbend.obs.websocket.client

import com.worxbend.obs.websocket.client.protocol.*
import ox.channels.{ActorRef, Channel, ChannelClosedException}
import ox.{discard, timeoutOption, uninterruptible}
import ox.either.catching

/** A session belongs to the callback passed to ObsClient.withTransport. */
final class ObsSession private[client] (
    val metadata: ConnectionMetadata,
    config: ObsConfig,
    dependencies: SessionDependencies,
    logic: ActorRef[SessionLogic]
):
  private def ask[A](f: SessionLogic => Either[ObsError, A]): Either[ObsError, A] =
    logic.ask(f).catching[ChannelClosedException].left.map(_ => ObsError.Closed).flatten

  /** Typed catalog request. A request type absent from the discovered capability set is rejected locally with
    * [[ObsError.UnsupportedRequest]]; an empty capability set therefore rejects every typed request. `RawRequest`
    * bypasses this check so vendor extensions and requests newer than the pinned catalog can reach the server.
    */
  def request[A](request: Request[A]): Either[ObsError, A] =
    if !capable(request) then Left(ObsError.UnsupportedRequest(request.requestType))
    else
      request.validate.left
        .map(SessionWire.malformed)
        .flatMap: _ =>
          rawRequest(request.requestType, request.requestData).flatMap(
            request.decodeResponse(_).left.map(SessionWire.malformed)
          )

  private def capable(request: Request[?]): Boolean = request match
    case _: RawRequest => true
    case _             => metadata.availableRequests.contains(request.requestType)

  /** Escape hatch for vendor extensions and requests newer than the pinned catalog. */
  def rawRequest(requestType: String, data: JsonObject = JsonObject.empty): Either[ObsError, JsonObject] =
    exchange(requestType, 7): id =>
      WireMessage(
        6,
        JsonObject(
          Map(
            "requestType" -> JsonValue.Str(requestType),
            "requestId" -> JsonValue.Str(id),
            "requestData" -> data
          )
        )
      )

  private def exchange(requestType: String, responseOpcode: Int)(
      message: String => WireMessage
  ): Either[ObsError, JsonObject] =
    val id = dependencies.nextRequestId()
    val reply = Channel.buffered[Either[ObsError, JsonObject]](1)
    try
      ask(_.register(id, requestType, responseOpcode, message(id), reply)).flatMap: _ =>
        timeoutOption(config.requestTimeout)(reply.receive()).getOrElse(Left(ObsError.Timeout(requestType)))
    finally uninterruptible(logic.tell(_.cancel(id, reply)).catching[ChannelClosedException].discard)

  /** Queue a server subscription change. Success means queued; the uncorrelated Identified acknowledgement is validated
    * asynchronously while the session remains ready.
    */
  def reidentify(subscriptions: EventSubscriptions): Either[ObsError, Unit] = ask(_.reidentify(subscriptions))

  /** Each subscriber receives future matching events independently. User code runs on its caller. */
  def withEvents[A](eventTypes: Set[String] = Set.empty, policy: OverflowPolicy = OverflowPolicy.Fail)(
      use: ObsSubscription => A
  ): Either[ObsError, A] =
    subscribe(eventTypes, policy, EventRepresentation.Typed)(use)

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
    try
      ask(_.subscribe(id, channel, eventTypes, policy, representation)).map: _ =>
        use(new ObsSubscription(channel, () => logic.ask(_.losses(id)).catching[ChannelClosedException].getOrElse(0L)))
    finally uninterruptible(logic.tell(_.unsubscribe(id, channel)).catching[ChannelClosedException].discard)

  /** Execute heterogeneous requests, preserving raw response data and each submitted position. Typed entries are
    * capability-checked like [[request]]; `RawRequest` entries bypass that check.
    */
  def batch(
      requests: Vector[Request[?]],
      execution: BatchExecution = BatchExecution.SerialRealtime,
      failurePolicy: BatchFailurePolicy = BatchFailurePolicy.Continue
  ): Either[ObsError, Vector[BatchResult]] =
    if requests.isEmpty then Right(Vector.empty)
    else if execution == BatchExecution.Parallel then
      Left(ObsError.InvalidConfiguration("Parallel batches cannot safely correlate results on supported OBS servers"))
    else
      val validated = requests.foldLeft[Either[ObsError, Unit]](Right(()))((acc, request) =>
        acc.flatMap: _ =>
          if !capable(request) then Left(ObsError.UnsupportedRequest(request.requestType))
          else request.validate.left.map(SessionWire.malformed)
      )
      validated.flatMap: _ =>
        val response = exchange("RequestBatch", 9): id =>
          WireMessage(
            8,
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
        response match
          case Left(error @ (ObsError.Transport(_, _) | ObsError.Timeout(_) | ObsError.Closed)) =>
            Left(ObsError.AmbiguousBatchOutcome(error))
          case Left(error) => Left(error)
          case Right(data) => decodeBatch(data, requests, failurePolicy)

  /** Example: typedBatch((BatchCall(GetVersion()), BatchCall(GetSceneList()))). */
  def typedBatch[Calls <: Tuple](
      calls: Calls,
      execution: BatchExecution = BatchExecution.SerialRealtime,
      failurePolicy: BatchFailurePolicy = BatchFailurePolicy.Continue
  )(using codec: BatchCodec[Calls]): Either[ObsError, BatchResults[Calls]] =
    batch(codec.requests(calls), execution, failurePolicy).map(codec.decode(calls, _))

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
    firstFailure >= 0 && firstFailure == resultCount - 1 || firstFailure < 0 && resultCount == requestCount

  def state: Either[ObsError, ConnectionState] = ask(s => Right(s.phase))

  /** Completes all outstanding requests and subscriptions immediately. */
  def close(): Unit = logic.ask(_.close()).catching[ChannelClosedException].discard
