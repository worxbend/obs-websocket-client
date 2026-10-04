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
        val normalized = config.copy(http = config.http.copy(host = config.http.host.trim))
        if normalized.http.host.isEmpty then Left(ConfigurationError.InvalidHttpHost)
        else if normalized.http.port < 0 || normalized.http.port > 65535 then Left(ConfigurationError.InvalidHttpPort)
        else
          normalized.obs.clientConfig.validate.left.map(_ => ConfigurationError.InvalidObsSettings).map(_ => normalized)

  /** Invalid startup configuration is terminal; diagnostics never render the source or secret values. */
  def read: Configuration = read(ConfigSource.default)

  def read(source: ConfigSource): Configuration = load(source).fold(
    error => throw new IllegalArgumentException(s"Invalid server configuration: $error"),
    identity
  )
