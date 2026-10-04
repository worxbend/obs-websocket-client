package com.worxbend.obs.websocket.client.server

import com.worxbend.obs.websocket.client.{ObsConfig, PasswordProvider}
import pureconfig.ConfigReader

private[server] final case class ObsSettings(url: String, password: Option[Sensitive]) derives ConfigReader:
  /** A blank password (e.g. an empty `OBS_WS_PASSWORD`) means no authentication. */
  def clientConfig: ObsConfig = ObsConfig(url, PasswordProvider.fixed(password.map(_.value).filter(_.trim.nonEmpty)))
  override def toString: String =
    s"ObsSettings(url=$url, password=${if password.isDefined then "<set>" else "<unset>"})"
