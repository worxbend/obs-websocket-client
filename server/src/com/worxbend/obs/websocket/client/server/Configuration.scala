package com.worxbend.obs.websocket.client.server

import pureconfig.{ConfigReader, ConfigSource}

private[server] enum ConfigurationError:
  case MissingOrMalformed, InvalidHttpHost, InvalidHttpPort, InvalidObsSettings

private[server] final case class Configuration(http: HttpConfig, obs: ObsSettings) derives ConfigReader

private[server] object Configuration:
  /** Loading is explicit so applications and tests can supply an alternate HOCON source. */
  def load(source: ConfigSource): Either[ConfigurationError, Configuration] =
    source
      .load[Configuration]
      .left
      .map(_ => ConfigurationError.MissingOrMalformed)
      .flatMap: config =>
        if config.http.host.trim.isEmpty then Left(ConfigurationError.InvalidHttpHost)
        else if config.http.port < 1 || config.http.port > 65535 then Left(ConfigurationError.InvalidHttpPort)
        else config.obs.clientConfig.validate.left.map(_ => ConfigurationError.InvalidObsSettings).map(_ => config)

  /** Invalid startup configuration is terminal; diagnostics never render the source or secret values. */
  def read: Configuration = read(ConfigSource.default)

  def read(source: ConfigSource): Configuration = load(source).fold(
    error => throw new IllegalArgumentException(s"Invalid server configuration: $error"),
    identity
  )
