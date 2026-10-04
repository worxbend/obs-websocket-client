package com.worxbend.obs.websocket.client.server

import pureconfig.ConfigSource

class ConfigurationSuite extends munit.FunSuite:
  private def source(host: String = "127.0.0.1", port: String = "8080", url: String = "ws://localhost:4455") =
    ConfigSource.string(s"""http { host = "$host", port = $port }, obs { url = "$url" }""")

  test("bundled HOCON loads loopback defaults without a password"):
    // Shield substitutions from ambient HTTP_HOST/HTTP_PORT/OBS_WS_URL/OBS_WS_PASSWORD so the
    // bundled defaults are asserted deterministically in any environment.
    import com.typesafe.config.ConfigFactory
    val shield = ConfigFactory.parseString(
      """HTTP_HOST="127.0.0.1", HTTP_PORT=8080, OBS_WS_URL="ws://localhost:4455", OBS_WS_PASSWORD=null"""
    )
    val resolved = shield.withFallback(ConfigFactory.parseResources("application.conf")).resolve()
    val config = Configuration.read(ConfigSource.fromConfig(resolved))
    assertEquals(
      (config.http, config.obs.clientConfig.passwordProvider.password()),
      (HttpConfig("127.0.0.1", 8080), Right(None))
    )

  test("missing required configuration is rejected"):
    assertEquals(Configuration.load(ConfigSource.string("")), Left(ConfigurationError.MissingOrMalformed))

  test("malformed HOCON is rejected without exposing source text"):
    assertEquals(Configuration.load(ConfigSource.string("secret {")), Left(ConfigurationError.MissingOrMalformed))

  test("wrong field type is rejected"):
    assertEquals(Configuration.load(source(port = "not-a-number")), Left(ConfigurationError.MissingOrMalformed))

  test("empty HTTP host is rejected"):
    assertEquals(Configuration.load(source(host = "")), Left(ConfigurationError.InvalidHttpHost))

  test("surrounding whitespace in the HTTP host is trimmed before validation and storage"):
    assertEquals(Configuration.load(source(host = " localhost ")).map(_.http), Right(HttpConfig("localhost", 8080)))

  test("whitespace-only HTTP host is rejected"):
    assertEquals(Configuration.load(source(host = "   ")), Left(ConfigurationError.InvalidHttpHost))

  test("port below range is rejected"):
    assertEquals(Configuration.load(source(port = "-1")), Left(ConfigurationError.InvalidHttpPort))

  test("port above range is rejected"):
    assertEquals(Configuration.load(source(port = "65536")), Left(ConfigurationError.InvalidHttpPort))

  test("invalid OBS settings are rejected"):
    assertEquals(Configuration.load(source(url = "http://localhost")), Left(ConfigurationError.InvalidObsSettings))

  test("secret values remain available to the provider but are redacted in configuration rendering"):
    val config = Configuration
      .load(
        ConfigSource.string(
          """http { host = "localhost", port = 8081 }, obs { url = "ws://localhost:4456", password = "top-secret" }"""
        )
      )
      .toOption
      .get
    assertEquals(config.obs.clientConfig.passwordProvider.password(), Right(Some("top-secret")))
    assert(!config.toString.contains("top-secret"))
    assert(config.toString.contains("url=ws://localhost:4456"))
    assert(config.toString.contains("password=<set>"))

  test("a blank OBS password is treated as no authentication"):
    val config = Configuration
      .load(
        ConfigSource.string(
          """http { host = "127.0.0.1", port = 8080 }, obs { url = "ws://localhost:4455", password = "" }"""
        )
      )
      .toOption
      .get
    assertEquals(config.obs.clientConfig.passwordProvider.password(), Right(None))

  test("invalid startup configuration terminates with a redacted diagnostic"):
    val error = intercept[IllegalArgumentException]:
      Configuration.read(ConfigSource.string("secret {"))
    assertEquals(error.getMessage, "Invalid server configuration: MissingOrMalformed")

  test("bundled HOCON substitutions accept environment-style overrides"):
    import com.typesafe.config.ConfigFactory
    val overrides = ConfigFactory.parseString(
      """HTTP_HOST="localhost", HTTP_PORT=8082, OBS_WS_URL="ws://localhost:4457", OBS_WS_PASSWORD="override-secret""""
    )
    val resolved = overrides.withFallback(ConfigFactory.parseResources("application.conf")).resolve()
    val config = Configuration.load(ConfigSource.fromConfig(resolved)).toOption.get
    assertEquals(
      (config.http, config.obs.url, config.obs.password.map(_.value)),
      (HttpConfig("localhost", 8082), "ws://localhost:4457", Some("override-secret"))
    )

  test("configuration rendering distinguishes an unset password without exposing wrapped secrets"):
    assertEquals(Sensitive("do-not-print").toString, "***")
    assertEquals(
      ObsSettings("ws://localhost:4455", None).toString,
      "ObsSettings(url=ws://localhost:4455, password=<unset>)"
    )

  test("parameterless configuration loader reads the default source"):
    assertEquals(Configuration.read, Configuration.read(ConfigSource.default))
