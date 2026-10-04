package com.worxbend.obs.websocket.client

import ox.channels.{Channel, ChannelClosed}
import ox.flow.Flow

/** Scoped best-effort metadata stream. A slow consumer drops new records; counters remain complete. */
final class ObsDiagnosticsSubscription private[client] (channel: Channel[SessionDiagnostic], losses: () => Long):
  def next(): Either[ObsError, SessionDiagnostic] = channel.receiveOrClosed() match
    case diagnostic: SessionDiagnostic                 => Right(diagnostic)
    case ChannelClosed.Error(error: SessionTerminated) => Left(error.error)
    case _                                             => Left(ObsError.Closed)

  /** Clean closure completes silently; a concrete failure is emitted once before completion. */
  def flow: Flow[Either[ObsError, SessionDiagnostic]] = Flow.usingEmit: emit =>
    var running = true
    while running do
      next() match
        case Left(ObsError.Closed) => running = false
        case result                =>
          emit(result)
          running = result.isRight

  def droppedDiagnostics: Long = losses()
