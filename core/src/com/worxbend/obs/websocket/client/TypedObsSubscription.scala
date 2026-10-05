package com.worxbend.obs.websocket.client

import com.worxbend.obs.websocket.client.protocol.{Event, EventSelector}
import ox.flow.Flow

/** Typed view of the same scoped event queue, with filtering performed on the consuming caller. */
final class TypedObsSubscription[E <: Event] private[client] (source: ObsSubscription, selector: EventSelector[E]):
  /** `Next.Ended` means clean end-of-stream; failures surface as `Next.Failed` with their concrete `ObsError`. Events
    * the selector rejects are skipped on the calling thread.
    */
  @scala.annotation.tailrec
  def next(): Next[E] = source.next() match
    case Next.Item(event) =>
      selector.select(event) match
        case Some(value) => Next.Item(value = value)
        case None        => next()
    case Next.Failed(error) => Next.Failed(error = error)
    case Next.Ended         => Next.Ended

  /** Clean closure completes silently; a concrete failure is emitted once before completion. */
  def flow: Flow[Either[ObsError, E]] = Next.drain(() => next())

  /** Total explicit policy drops on the shared source queue, retained after its scope or session ends. */
  def droppedEvents: Long = source.droppedEvents
