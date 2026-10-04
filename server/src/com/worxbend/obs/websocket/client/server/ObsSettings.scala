package com.worxbend.obs.websocket.client.server

import com.worxbend.obs.websocket.client.{ObsConfig, PasswordProvider}
import pureconfig.ConfigReader

private[server] final case class ObsSettings(url: String, password: Option[Sensitive]) derives ConfigReader:
  def clientConfig: ObsConfig = ObsConfig(url, PasswordProvider.fixed(password.map(_.value)))
  override def toString: String = s"ObsSettings(url=<redacted>, password=$password)"
