package com.worxbend.obs.websocket.client.util

/** Rendering for internal defect diagnostics. */
private[client] object Defect:
  /** The throwable's fully qualified class name. Exception messages can carry peer-controlled or sensitive detail, so
    * their text never reaches error output.
    */
  def describe(cause: Throwable): String = cause.getClass.getName
