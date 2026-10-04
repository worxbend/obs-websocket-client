package com.worxbend.obs.websocket.client.server

import pureconfig.ConfigReader

private[server] final case class HttpConfig(host: String, port: Int) derives ConfigReader
