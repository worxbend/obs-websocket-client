package com.worxbend.obs.websocket.client

import com.worxbend.obs.websocket.client.protocol.*
import ox.channels.{ActorRef, Channel, ChannelClosedException}
import ox.{discard, timeoutOption, uninterruptible}
import ox.either.catching
import scala.util.control.NonFatal
import scala.concurrent.duration.*

/** Owns one request's total registration/wait budget and unconditional owner-checked cancellation.
  * User-supplied preparation runs on the caller, never inside the coordinating actor.
  */
final private[client] class RequestExchange(
  config:       ObsConfig,
  dependencies: SessionDependencies,
  logic:        ActorRef[SessionLogic],
  ids:          CorrelationIds,
):
  def run(
    requestType:      String,
    responseOpcode:   Int,
    options:          RequestOptions,
    postRegistration: ObsError => ObsError = identity,
  )(message: String => WireMessage): Either[ObsError, JsonObject] =
    options.deadline(default = config.requestTimeout).flatMap: budget =>
      val started = dependencies.nanoTime()
      ids.next(seed = dependencies.nextRequestId()).flatMap: id =>
        val reply = Channel.buffered[Either[ObsError, JsonObject]](1)
        // The actor publishes admission separately: an interrupted ask may still have admitted a request.
        val admission  = Channel.buffered[Boolean](1)
        var outcome    = DiagnosticOutcome.Cancelled
        var registered = false
        var submitted  = false
        try
          val remaining = budget - (dependencies.nanoTime() - started).nanos
          val completed = (if remaining <= Duration.Zero then None
                           else
                             timeoutOption(remaining):
                               val prepared = message(id)
                               submitted = true
                               logic.ask: state =>
                                 val registered =
                                   if dependencies.nanoTime() - started >= budget.toNanos then
                                     Left(ObsError.Timeout(operation = requestType))
                                   else
                                     state.register(
                                       id          = id,
                                       requestType = requestType,
                                       opcode      = responseOpcode,
                                       message     = prepared,
                                       reply       = reply,
                                     )
                                 admission.trySendOrClosed(registered.isRight).discard
                                 registered
                               .catching[ChannelClosedException].left.map(_ => ObsError.Closed).flatten.flatMap(_ =>
                                 reply.receive()
                               )
          ).getOrElse(reply.tryReceive().getOrElse(Left(ObsError.Timeout(operation = requestType))))
          outcome = DiagnosticOutcome.from(result = completed)
          val admitted = admission.tryReceive()
          registered = admitted.contains(true)
          // A canceled ask can race an admission already in progress. Never claim a batch did not execute
          // merely because its actor acknowledgement has not arrived yet.
          val mayHaveRegistered = registered || (submitted && admitted.isEmpty)
          completed.left.map:
            case error @ (ObsError.Transport(_, _) | ObsError.Timeout(_) | ObsError.Closed) if mayHaveRegistered =>
              postRegistration(error)
            case error => error
        finally
          // This tell is queued even when the timed registration ask was interrupted. Its FIFO position removes
          // late admissions before subsequent actor work. Cleanup enqueue/scope joining are not operation budget.
          val elapsed = dependencies.nanoTime() - started
          uninterruptible:
            tellSafely: state =>
              state.cancel(id = id, owner = reply)
              // Cancellation may leave admission queued; FIFO cleanup observes it after the registration invocation.
              if registered || admission.tryReceive().contains(true) then
                state.requestFinished(
                  requestType  = requestType,
                  id           = id,
                  elapsedNanos = elapsed,
                  outcome      = outcome,
                )

  private def tellSafely(f: SessionLogic => Unit): Unit =
    logic.tell(state =>
      try f(state)
      catch case NonFatal(_) => ()
    ).catching[ChannelClosedException].discard
