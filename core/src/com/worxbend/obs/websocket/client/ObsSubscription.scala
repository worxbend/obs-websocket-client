package com.worxbend.obs.websocket.client

import com.worxbend.obs.websocket.client.protocol.Event
import ox.channels.{Channel, ChannelClosed}
import ox.flow.Flow
import scala.concurrent.duration.FiniteDuration

enum OverflowPolicy:
  case Fail, DropNewest, DropOldest

private[client] final class SessionTerminated(val error: ObsError) extends RuntimeException("OBS session terminated")

/** A bounded, ordered subscription. Consume it inside the withEvents callback. */
final class ObsSubscription private[client] (
    channel: Channel[Event],
    losses: () => Long,
    nanoTime: () => Long = () => System.nanoTime()
):
  /** `Left(ObsError.Closed)` means clean end-of-stream: the subscription was unsubscribed, its `withEvents` scope
    * exited, or the session closed. Failures — overflow, transport loss, protocol errors — surface as their concrete
    * `ObsError`, never as a bare `Closed`.
    */
  def next(): Either[ObsError, Event] = channel.receiveOrClosed() match
    case event: Event                                    => Right(event)
    case ChannelClosed.Error(failure: SessionTerminated) => Left(failure.error)
    case _: ChannelClosed                                => Left(ObsError.Closed)

  /** Clean closure completes silently; a concrete failure is emitted once before completion. */
  def flow: Flow[Either[ObsError, Event]] = Flow.usingEmit: emit =>
    var running = true
    while running do
      next() match
        case Left(ObsError.Closed) => running = false
        case result                =>
          emit(result)
          running = result.isRight

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
