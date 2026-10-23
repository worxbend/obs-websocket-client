package com.worxbend.obs.websocket.client

import ox.channels.{Channel, ChannelClosed}
import ox.flow.Flow

/** Scoped best-effort metadata stream. A slow consumer drops new records; counters remain complete. A session failure
  * surfaces as its concrete `ObsError`; a clean close or unsubscribe completes the stream silently.
  */
final class ObsDiagnosticsSubscription private[client] (channel: Channel[SessionDiagnostic], losses: () => Long):
  /** `Next.Ended` on clean close or unsubscribe; `Next.Failed` carries the concrete session failure. */
  def next(): Next[SessionDiagnostic] = channel.receiveOrClosed() match
    case diagnostic: SessionDiagnostic => Next.Item(value = diagnostic)
    case ChannelClosed.Error(failure: SessionTerminated) if failure.error != ObsError.Closed =>
      Next.Failed(error = failure.error)
    case _: ChannelClosed => Next.Ended

  /** Clean closure completes silently; a concrete failure is emitted once before completion. */
  def flow: Flow[Either[ObsError, SessionDiagnostic]] = Next.drain(() => next())

  /** Total records dropped because the consumer fell behind; session counters remain complete. */
  def droppedDiagnostics: Long = losses()
