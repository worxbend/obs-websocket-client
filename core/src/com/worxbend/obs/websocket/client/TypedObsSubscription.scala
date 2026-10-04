package com.worxbend.obs.websocket.client

import com.worxbend.obs.websocket.client.protocol.{Event, EventSelector}
import ox.flow.Flow

/** Typed view of the same scoped event queue, with filtering performed on the consuming caller. */
final class TypedObsSubscription[E <: Event] private[client] (source: ObsSubscription, selector: EventSelector[E]):
  @scala.annotation.tailrec
  def next(): Either[ObsError, E] = source.next() match
    case Left(error)  => Left(error)
    case Right(event) =>
      selector.select(event) match
        case Some(value) => Right(value)
        case None        => next()

  /** Clean closure completes silently; a concrete failure is emitted once before completion. */
  def flow: Flow[Either[ObsError, E]] = Flow.usingEmit: emit =>
    var running = true
    while running do
      next() match
        case Left(ObsError.Closed) => running = false
        case result                =>
          emit(result)
          running = result.isRight

  def droppedEvents: Long = source.droppedEvents
