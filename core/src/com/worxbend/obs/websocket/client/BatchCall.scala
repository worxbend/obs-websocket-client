package com.worxbend.obs.websocket.client

import com.worxbend.obs.websocket.client.protocol.Request

/** Preserve each result type in a heterogeneous tuple passed to typedBatch. */
final case class BatchCall[A](request: Request[A])

enum TypedBatchResult[+A]:
  case Completed(result: Either[ObsError, A])
  case NotExecuted

type BatchResults[Calls <: Tuple] <: Tuple = Calls match
  case EmptyTuple           => EmptyTuple
  case BatchCall[a] *: tail => TypedBatchResult[a] *: BatchResults[tail]

sealed trait BatchCodec[Calls <: Tuple]:
  def requests(calls: Calls): Vector[Request[?]]
  def decode(calls: Calls, results: Vector[BatchResult]): BatchResults[Calls]

object BatchCodec:
  given empty: BatchCodec[EmptyTuple] with
    def requests(calls: EmptyTuple): Vector[Request[?]] = Vector.empty
    def decode(calls: EmptyTuple, results: Vector[BatchResult]): EmptyTuple = EmptyTuple

  given cons[A, Tail <: Tuple](using tail: BatchCodec[Tail]): BatchCodec[BatchCall[A] *: Tail] with
    def requests(calls: BatchCall[A] *: Tail): Vector[Request[?]] = calls.head.request +: tail.requests(calls.tail)
    def decode(calls: BatchCall[A] *: Tail, results: Vector[BatchResult]): BatchResults[BatchCall[A] *: Tail] =
      val head = results.head match
        case BatchResult.Completed(_, result) =>
          TypedBatchResult.Completed(
            result.flatMap(calls.head.request.decodeResponse(_).left.map(SessionWire.malformed))
          )
        case BatchResult.NotExecuted(_) => TypedBatchResult.NotExecuted
      head *: tail.decode(calls.tail, results.tail)
