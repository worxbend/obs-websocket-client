package com.worxbend.obs.websocket.client

import ox.channels.Channel
import ox.discard

/** All access is confined to SessionLogic's actor. */
private[client] final case class DiagnosticRegistry(
    entries: Map[String, (Channel[SessionDiagnostic], Long)] = Map.empty
):
  def publish(event: SessionDiagnostic): DiagnosticRegistry =
    copy(entries = entries.map: (id, entry) =>
      val (channel, dropped) = entry
      channel.trySendOrClosed(event) match
        case true  => id -> entry
        case false => id -> (channel, dropped + 1)
        case _     => id -> entry)

  def close(): Unit = entries.values.foreach(_._1.doneOrClosed().discard)
