package com.worxbend.obs.websocket.client.server

import java.net.{InetAddress, URI}
import java.nio.file.Paths
import ox.*
import org.slf4j.LoggerFactory
import pureconfig.ConfigSource
import _root_.sttp.tapir.server.netty.sync.NettySyncServer

/** Local HTTP sample with a loopback bind by default. */
object Main:
  private val logger = LoggerFactory.getLogger(getClass)

  /** An optional first argument names a HOCON configuration file; the standard source applies otherwise. */
  def main(args: Array[String]): Unit = ox.supervised:
    serve(config = Configuration.read(source = configSource(args = args)))

  private[server] def configSource(args: Array[String]): ConfigSource =
    if args.isEmpty then ConfigSource.default else ConfigSource.file(Paths.get(args(0)))

  private[server] def serve(config: Configuration)(using Ox): Unit =
    val service = ObsReadService.live(config = config.obs.clientConfig)
    val binding = useInScope(
      NettySyncServer()
        .host(config.http.host)
        .port(config.http.port)
        .addEndpoints(Endpoints.all(service = service))
        .start()
    )(_.stop())
    logger.info("Swagger UI: {}", swaggerUrl(host = config.http.host, port = binding.port))
    never

  /** Wildcard binds are reachable through loopback; URI rendering brackets IPv6 hosts. */
  private[server] def swaggerUrl(host: String, port: Int): String =
    val reachable = if InetAddress.getByName(host).isAnyLocalAddress then "127.0.0.1" else host
    new URI("http", null, reachable, port, "/docs", null, null).toString
