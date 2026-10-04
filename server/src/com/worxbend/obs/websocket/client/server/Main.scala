package com.worxbend.obs.websocket.client.server

import ox.*
import _root_.sttp.tapir.server.netty.sync.NettySyncServer

/** Local HTTP sample with a loopback bind by default. */
object Main extends OxApp.Simple:
  override def run(using Ox): Unit =
    val config = Configuration.read
    val service = ObsReadService.live(config.obs.clientConfig)
    val binding = useInScope(
      NettySyncServer().host(config.http.host).port(config.http.port).addEndpoints(Endpoints.all(service)).start()
    )(_.stop())
    println(s"Swagger UI: http://${config.http.host}:${binding.port}/docs")
    never
