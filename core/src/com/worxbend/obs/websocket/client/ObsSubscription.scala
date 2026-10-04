package com.worxbend.obs.websocket.client

import com.worxbend.obs.websocket.client.protocol.Event
import ox.channels.{Channel, ChannelClosed}
import ox.flow.Flow
import scala.concurrent.duration.FiniteDuration

enum OverflowPolicy:
  case Fail, DropNewest, DropOldest

/** One subscription read: an item, a concrete failure, or clean end-of-stream. */
enum Next[+A]:
  case Item(value: A)
  case Failed(error: ObsError)
  case Ended

private[client] final class SessionTerminated(val error: ObsError) extends RuntimeException("OBS session terminated")

/** A bounded, ordered subscription. Consume it inside the withEvents callback. */
final class ObsSubscription private[client] (
    channel: Channel[Event],
    losses: () => Long,
    nanoTime: () => Long = () => System.nanoTime()
):
  /** `Next.Ended` means clean end-of-stream: the subscription was unsubscribed, its `withEvents` scope exited, or the
    * session closed. Failures — overflow, transport loss, protocol errors — surface as `Next.Failed` with their
    * concrete `ObsError`, never as a bare `Closed`.
    */
  def next(): Next[Event] = channel.receiveOrClosed() match
    case event: Event                                                                        => Next.Item(event)
    case ChannelClosed.Error(failure: SessionTerminated) if failure.error != ObsError.Closed =>
      Next.Failed(failure.error)
    case _: ChannelClosed => Next.Ended

  /** Clean closure completes silently; a concrete failure is emitted once before completion. */
  def flow: Flow[Either[ObsError, Event]] = Flow.usingEmit: emit =>
    var running = true
    while running do
      next() match
        case Next.Ended         => running = false
        case Next.Failed(error) =>
          emit(Left(error))
          running = false
        case Next.Item(event) => emit(Right(event))

  /** Total explicit policy drops, retained by this subscription after its scope or session ends. */
  def droppedEvents: Long = losses()

  /** Explicit lossy telemetry sampling. The key function runs on a scoped consumer worker, never the session actor. Key
    * cardinality and queued windows are bounded; source overflow remains controlled by withEvents' policy. A terminal
    * source error discards the unfinished window and terminates this stream.
    */
  def withLatestBy[K, A](interval: FiniteDuration, maxKeys: Int)(key: Event => K)(
      use: SampledSubscription[K] => A
  ): Either[ObsError, A] =
    SampledSubscription.use(this, interval, maxKeys, nanoTime)(key)(use)
