package com.worxbend.obs.websocket.client

import com.worxbend.obs.websocket.client.protocol.Event
import com.worxbend.obs.websocket.client.util.Defect
import ox.*
import ox.channels.{Channel, ChannelClosed}
import ox.flow.Flow
import scala.concurrent.duration.*
import scala.annotation.tailrec
import scala.util.control.NonFatal
import java.util.concurrent.atomic.AtomicLong

/** A bounded stream of latest-value windows. Intermediate windows may be replaced if the consumer is slow. A defect
  * thrown by the key function terminates the stream with `Next.Failed(ObsError.InternalError)` carrying the cause.
  */
final class SampledSubscription[K] private[client] (
  channel: Channel[EventWindow[K]],
  drops:   AtomicLong,
  source:  ObsSubscription,
):
  /** `Next.Ended` means clean end-of-stream; a terminal source failure or a key-function defect surfaces as
    * `Next.Failed` with its concrete `ObsError`.
    */
  def next(): Next[EventWindow[K]] = channel.receiveOrClosed() match
    case window: EventWindow[?]                        => Next.Item(value = window.asInstanceOf[EventWindow[K]])
    case ChannelClosed.Error(error: SessionTerminated) => Next.Failed(error = error.error)
    case _                                             => Next.Ended

  /** Clean closure completes silently; a concrete failure is emitted once before completion. */
  def flow: Flow[Either[ObsError, EventWindow[K]]] = Next.drain(() => next())

  /** Total windows replaced before the consumer read them, retained after the stream ends. */
  def droppedWindows: Long = drops.get()

  /** Total explicit policy drops on the underlying source subscription. */
  def droppedEvents: Long = source.droppedEvents

private[client] object SampledSubscription:
  def use[K, A](source: ObsSubscription, interval: FiniteDuration, maxKeys: Int, nanoTime: () => Long)(
    key: Event => K
  )(consume: SampledSubscription[K] => A): Either[ObsError, A] =
    if interval <= Duration.Zero || maxKeys <= 0 then
      Left(ObsError.InvalidConfiguration(message = "Sampling interval and key limit must be positive"))
    else
      Right(supervised:
        val channel = Channel.buffered[EventWindow[K]](1)
        val dropped = new AtomicLong(0L)
        forkDiscard(
          run(
            source   = source,
            output   = channel,
            dropped  = dropped,
            interval = interval,
            maxKeys  = maxKeys,
            nanoTime = nanoTime,
          )(key = key)
        )
        consume(new SampledSubscription(channel, dropped, source)))

  private def run[K](
    source:   ObsSubscription,
    output:   Channel[EventWindow[K]],
    dropped:  AtomicLong,
    interval: FiniteDuration,
    maxKeys:  Int,
    nanoTime: () => Long,
  )(key: Event => K): Unit =
    var running = true
    while running do
      checkInterrupt()
      val deadline = nanoTime() + interval.toNanos
      collectWindow(source = source, deadline = deadline, maxKeys = maxKeys, nanoTime = nanoTime)(key = key) match
        case Next.Item(window)  => publishWindow(window = window, output = output, dropped = dropped)
        case Next.Failed(error) =>
          output.errorOrClosed(new SessionTerminated(error = error)).discard
          running = false
        case Next.Ended =>
          output.doneOrClosed().discard
          running = false

  /** A terminal source result discards the partial window, preserving immediate stream termination. */
  private def collectWindow[K](source: ObsSubscription, deadline: Long, maxKeys: Int, nanoTime: () => Long)(
    key: Event => K
  ): Next[EventWindow[K]] =
    @tailrec
    def collect(window: EventWindow[K]): Next[EventWindow[K]] =
      val remaining = deadline - nanoTime()
      val event     = if remaining <= 0 then None else timeoutOption(remaining.nanos)(source.next())
      event match
        case Some(Next.Item(value)) =>
          addEvent(window = window, value = value, maxKeys = maxKeys)(key = key) match
            case Right(updated) => collect(window = updated)
            case Left(error)    => Next.Failed(error = error)
        case Some(Next.Failed(error)) => Next.Failed(error = error)
        case Some(Next.Ended)         => Next.Ended
        case None                     => Next.Item(value = window)
    collect(window = EventWindow[K]())

  /** Contain key-function defects without swallowing interruption or terminating the owning scope. */
  private def addEvent[K](window: EventWindow[K], value: Event, maxKeys: Int)(
    key: Event => K
  ): Either[ObsError, EventWindow[K]] =
    try Right(window.add(key = key(value), event = value, maxKeys = maxKeys))
    catch
      case NonFatal(cause) =>
        Left(ObsError.InternalError(message = s"Sampling key function failed: ${Defect.describe(cause = cause)}"))

  private def publishWindow[K](window: EventWindow[K], output: Channel[EventWindow[K]], dropped: AtomicLong): Unit =
    if window.values.nonEmpty && !output.trySend(window) then
      output.tryReceive().foreach(_ => dropped.incrementAndGet().discard)
      output.trySend(window).discard
