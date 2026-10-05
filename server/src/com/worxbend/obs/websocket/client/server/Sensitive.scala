package com.worxbend.obs.websocket.client.server

import pureconfig.ConfigReader

/** Secret-bearing values are never printed by configuration rendering. */
final private[server] case class Sensitive(value: String):
  override def toString: String = "***"

private[server] object Sensitive:
  given ConfigReader[Sensitive] = ConfigReader[String].map(Sensitive(_))
