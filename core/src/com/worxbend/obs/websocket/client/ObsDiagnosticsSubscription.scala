package com.worxbend.obs.websocket.client

import ox.channels.{Channel, ChannelClosed}
import ox.flow.Flow

/** Scoped best-effort metadata stream. A slow consumer drops new records; counters remain complete. A session failure
  * surfaces as its concrete `ObsError`; a clean close or unsubscribe completes the stream silently.
  */
final class ObsDiagnosticsSubscription private[client] (channel: Channel[SessionDiagnostic], losses: () => Long):
  /** `Left(ObsError.Closed)` means clean end-of-stream; a concrete session failure is returned with its `ObsError`. */
  def next(): Either[ObsError, SessionDiagnostic] = channel.receiveOrClosed() match
    case diagnostic: SessionDiagnostic                 => Right(diagnostic)
    case ChannelClosed.Error(error: SessionTerminated) => Left(error.error)
    case _                                             => Left(ObsError.Closed)

  private def read(): Next[SessionDiagnostic] = next() match
    case Right(diagnostic)     => Next.Item(value = diagnostic)
    case Left(ObsError.Closed) => Next.Ended
    case Left(error)           => Next.Failed(error = error)

  /** Clean closure completes silently; a concrete failure is emitted once before completion. */
  def flow: Flow[Either[ObsError, SessionDiagnostic]] = Next.drain(() => read())

  /** Total records dropped because the consumer fell behind; session counters remain complete. */
  def droppedDiagnostics: Long = losses()
