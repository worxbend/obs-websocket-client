package com.worxbend.obs.websocket.client.protocol

/** Typed event selection. Generated event companions provide a selector that checks the actual decoded subtype. */
final class EventSelector[E <: Event] private (
    val eventType: String,
    val select: Event => Option[E]
)

object EventSelector:
  def apply[E <: Event](eventType: String)(select: Event => Option[E]): EventSelector[E] =
    new EventSelector(eventType, select)
