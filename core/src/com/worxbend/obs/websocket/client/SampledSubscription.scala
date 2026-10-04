package com.worxbend.obs.websocket.client

import com.worxbend.obs.websocket.client.protocol.Event
import ox.*
import ox.channels.{Channel, ChannelClosed}
import ox.flow.Flow
import scala.concurrent.duration.*
import java.util.concurrent.atomic.AtomicLong

/** A bounded stream of latest-value windows. Intermediate windows may be replaced if the consumer is slow. */
final class SampledSubscription[K] private[client] (
    channel: Channel[EventWindow[K]],
    drops: AtomicLong,
    source: ObsSubscription
):
  def next(): Either[ObsError, EventWindow[K]] = channel.receiveOrClosed() match
    case window: EventWindow[?]                        => Right(window.asInstanceOf[EventWindow[K]])
    case ChannelClosed.Error(error: SessionTerminated) => Left(error.error)
    case _                                             => Left(ObsError.Closed)

  /** Clean closure completes silently; a concrete failure is emitted once before completion. */
  def flow: Flow[Either[ObsError, EventWindow[K]]] = Flow.usingEmit: emit =>
    var running = true
    while running do
      next() match
        case Left(ObsError.Closed) => running = false
        case result                =>
          emit(result)
          running = result.isRight

  def droppedWindows: Long = drops.get()
  def droppedEvents: Long = source.droppedEvents

private[client] object SampledSubscription:
  def use[K, A](source: ObsSubscription, interval: FiniteDuration, maxKeys: Int, nanoTime: () => Long)(
      key: Event => K
  )(consume: SampledSubscription[K] => A): Either[ObsError, A] =
    if interval <= Duration.Zero || maxKeys <= 0 then
      Left(ObsError.InvalidConfiguration("Sampling interval and key limit must be positive"))
    else
      Right(supervised:
        val channel = Channel.buffered[EventWindow[K]](1)
        val dropped = new AtomicLong(0L)
        forkDiscard(run(source, channel, dropped, interval, maxKeys, nanoTime)(key))
        consume(new SampledSubscription(channel, dropped, source)))

  private def run[K](
      source: ObsSubscription,
      output: Channel[EventWindow[K]],
      dropped: AtomicLong,
      interval: FiniteDuration,
      maxKeys: Int,
      nanoTime: () => Long
  )(key: Event => K): Unit =
    var running = true
    while running do
      checkInterrupt()
      val deadline = nanoTime() + interval.toNanos
      var window = EventWindow[K]()
      var collecting = true
      while collecting && running do
        val remaining = deadline - nanoTime()
        val event = if remaining <= 0 then None else timeoutOption(remaining.nanos)(source.next())
        event match
          case Some(Right(value)) => window = window.add(key(value), value, maxKeys)
          case Some(Left(error))  =>
            output.errorOrClosed(new SessionTerminated(error)).discard
            running = false
          case None => collecting = false
      if running && window.values.nonEmpty then
        if !output.trySend(window) then
          output.tryReceive().foreach(_ => dropped.incrementAndGet().discard)
          output.trySend(window).discard
