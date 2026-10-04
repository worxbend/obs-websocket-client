package com.worxbend.obs.websocket.client.util

/** Rendering for internal defect diagnostics. */
private[client] object Defect:
  /** A throwable's message, falling back to its class name for message-less throwables. */
  def describe(cause: Throwable): String = Option(cause.getMessage).getOrElse(cause.getClass.getSimpleName)
