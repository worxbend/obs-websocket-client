package com.worxbend.obs.websocket.client

import com.worxbend.obs.websocket.client.protocol.Event
import ox.channels.{Channel, ChannelClosed}
import ox.flow.Flow
import scala.concurrent.duration.FiniteDuration

/** How a subscription's bounded per-subscriber queue (sized by `ObsConfig.subscriptionCapacity`) behaves when the
  * consumer falls behind and it fills.
  */
enum OverflowPolicy:
  /** Fail this subscription with `ObsError.Overflow("event subscription")` and remove it. The consumer observes
    * `Next.Failed`; the session and other subscriptions keep running.
    */
  case Fail

  /** Discard the incoming event. The consumer keeps the oldest queued events and observes the loss via
    * `droppedEvents`.
    */
  case DropNewest

  /** Evict the oldest queued event to make room for the incoming one. The consumer keeps the newest events and
    * observes the loss via `droppedEvents`.
    */
  case DropOldest

/** One subscription read: an item, a concrete failure, or clean end-of-stream. */
enum Next[+A]:
  case Item(value: A)
  case Failed(error: ObsError)
  case Ended

object Next:
  /** Every subscription flow shares this drain loop: items pass through, a concrete failure is emitted once before
    * completion, and a clean end completes silently. Only the mapping from a source read to the tri-state differs per
    * subscription type.
    */
  private[client] def drain[A](read: () => Next[A]): Flow[Either[ObsError, A]] = Flow.usingEmit: emit =>
    var running = true
    while running do
      read() match
        case Next.Item(value)   => emit(Right(value))
        case Next.Failed(error) =>
          emit(Left(error))
          running = false
        case Next.Ended => running = false

final private[client] class SessionTerminated(val error: ObsError) extends RuntimeException("OBS session terminated")

/** A bounded, ordered subscription. Consume it inside the withEvents callback. */
final class ObsSubscription private[client] (
  channel:  Channel[Event],
  losses:   () => Long,
  nanoTime: () => Long = () => System.nanoTime(),
):
  /** `Next.Ended` means clean end-of-stream: the subscription was unsubscribed, its `withEvents` scope exited, or the
    * session closed. Failures — overflow, transport loss, protocol errors — surface as `Next.Failed` with their
    * concrete `ObsError`, never as a bare `Closed`.
    */
  def next(): Next[Event] = channel.receiveOrClosed() match
    case event: Event                                                                        => Next.Item(value = event)
    case ChannelClosed.Error(failure: SessionTerminated) if failure.error != ObsError.Closed =>
      Next.Failed(error = failure.error)
    case _: ChannelClosed => Next.Ended

  /** Clean closure completes silently; a concrete failure is emitted once before completion. */
  def flow: Flow[Either[ObsError, Event]] = Next.drain(() => next())

  /** Total explicit policy drops, retained by this subscription after its scope or session ends. */
  def droppedEvents: Long = losses()

  /** Explicit lossy telemetry sampling. The key function runs on a scoped consumer worker, never the session actor. Key
    * cardinality and queued windows are bounded; source overflow remains controlled by withEvents' policy. A terminal
    * source error discards the unfinished window and terminates this stream. A defect thrown by the key function
    * terminates the stream with `Next.Failed(ObsError.InternalError)` instead of escaping the scope.
    */
  def withLatestBy[K, A](interval: FiniteDuration, maxKeys: Int)(key: Event => K)(
    use: SampledSubscription[K] => A
  ): Either[ObsError, A] =
    SampledSubscription
      .use(source = this, interval = interval, maxKeys = maxKeys, nanoTime = nanoTime)(key = key)(consume = use)
