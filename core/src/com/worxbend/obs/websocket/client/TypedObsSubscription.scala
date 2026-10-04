package com.worxbend.obs.websocket.client

import com.worxbend.obs.websocket.client.protocol.{Event, EventSelector}
import ox.flow.Flow

/** Typed view of the same scoped event queue, with filtering performed on the consuming caller. */
final class TypedObsSubscription[E <: Event] private[client] (source: ObsSubscription, selector: EventSelector[E]):
  @scala.annotation.tailrec
  def next(): Next[E] = source.next() match
    case Next.Item(event) =>
      selector.select(event) match
        case Some(value) => Next.Item(value)
        case None        => next()
    case Next.Failed(error) => Next.Failed(error)
    case Next.Ended         => Next.Ended

  /** Clean closure completes silently; a concrete failure is emitted once before completion. */
  def flow: Flow[Either[ObsError, E]] = Flow.usingEmit: emit =>
    var running = true
    while running do
      next() match
        case Next.Ended         => running = false
        case Next.Failed(error) =>
          emit(Left(error))
          running = false
        case Next.Item(event) => emit(Right(event))

  def droppedEvents: Long = source.droppedEvents
