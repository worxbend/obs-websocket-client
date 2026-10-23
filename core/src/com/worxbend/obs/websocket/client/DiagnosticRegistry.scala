package com.worxbend.obs.websocket.client

import ox.channels.Channel
import ox.discard
import java.util.concurrent.atomic.AtomicLong

/** All access is confined to SessionLogic's actor. Each subscriber owns its drop counter, so counts outlive the entry. */
final private[client] case class DiagnosticRegistry(
  entries: Map[String, (Channel[SessionDiagnostic], AtomicLong)] = Map.empty
):
  /** Full buffer drops newest and counts it; a closed channel counts nothing. */
  def publish(event: SessionDiagnostic): Unit =
    entries.valuesIterator.foreach: (channel, dropped) =>
      if channel.trySendOrClosed(event) == false then dropped.incrementAndGet().discard

  /** Clean termination: observers complete silently. */
  def close(): Unit = entries.values.foreach(_._1.doneOrClosed().discard)

  /** Session failure: observers learn the concrete error, terminated exactly like event subscriptions. */
  def fail(error: ObsError): Unit =
    entries.values.foreach(_._1.errorOrClosed(SessionTerminated(error)).discard)
