package com.worxbend.obs.websocket.client.server

import pureconfig.ConfigReader

final private[server] case class HttpConfig(host: String, port: Int) derives ConfigReader
