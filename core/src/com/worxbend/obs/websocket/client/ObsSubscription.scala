package com.worxbend.obs.websocket.client

import com.worxbend.obs.websocket.client.protocol.Event
import ox.channels.{Channel, ChannelClosed}
import ox.flow.Flow

enum OverflowPolicy:
  case Fail, DropNewest, DropOldest

private[client] final class SessionTerminated(val error: ObsError) extends RuntimeException("OBS session terminated")

/** A bounded, ordered subscription. Consume it inside the withEvents callback. */
final class ObsSubscription private[client] (channel: Channel[Event], losses: () => Long):
  def next(): Either[ObsError, Event] = channel.receiveOrClosed() match
    case event: Event                                    => Right(event)
    case ChannelClosed.Error(failure: SessionTerminated) => Left(failure.error)
    case _: ChannelClosed                                => Left(ObsError.Closed)

  /** Emits a final Left on overflow/disconnection, then completes. */
  def flow: Flow[Either[ObsError, Event]] = Flow.usingEmit: emit =>
    var running = true
    while running do
      val result = next()
      emit(result)
      running = result.isRight

  def droppedEvents: Long = losses()
