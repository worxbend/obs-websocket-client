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
  def next(): Next[EventWindow[K]] = channel.receiveOrClosed() match
    case window: EventWindow[?]                        => Next.Item(window.asInstanceOf[EventWindow[K]])
    case ChannelClosed.Error(error: SessionTerminated) => Next.Failed(error.error)
    case _                                             => Next.Ended

  /** Clean closure completes silently; a concrete failure is emitted once before completion. */
  def flow: Flow[Either[ObsError, EventWindow[K]]] = Flow.usingEmit: emit =>
    var running = true
    while running do
      next() match
        case Next.Ended         => running = false
        case Next.Failed(error) =>
          emit(Left(error))
          running = false
        case Next.Item(window) => emit(Right(window))

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
          case Some(Next.Item(value))   => window = window.add(key(value), value, maxKeys)
          case Some(Next.Failed(error)) =>
            output.errorOrClosed(new SessionTerminated(error)).discard
            running = false
          case Some(Next.Ended) =>
            output.doneOrClosed().discard
            running = false
          case None => collecting = false
      if running && window.values.nonEmpty then
        if !output.trySend(window) then
          output.tryReceive().foreach(_ => dropped.incrementAndGet().discard)
          output.trySend(window).discard
