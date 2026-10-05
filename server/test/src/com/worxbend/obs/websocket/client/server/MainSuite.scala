package com.worxbend.obs.websocket.client.server

class MainSuite extends munit.FunSuite:
  private val loopbackSwaggerUrl: String = "http://127.0.0.1:8080/docs"

  test("swagger URL substitutes loopback for the IPv4 wildcard bind"):
    assertEquals(Main.swaggerUrl(host = "0.0.0.0", port = 8080), loopbackSwaggerUrl)

  test("swagger URL substitutes loopback for the IPv6 wildcard bind"):
    assertEquals(Main.swaggerUrl(host = "::", port = 8080), loopbackSwaggerUrl)

  test("swagger URL brackets an IPv6 host"):
    assertEquals(Main.swaggerUrl(host = "::1", port = 8080), "http://[::1]:8080/docs")

  test("swagger URL keeps a routable host unchanged"):
    assertEquals(Main.swaggerUrl(host = "127.0.0.1", port = 8080), loopbackSwaggerUrl)

  test("no arguments select the default configuration source"):
    assertEquals(
      Configuration.load(source = Main.configSource(args = Array.empty)),
      Configuration.load(source = pureconfig.ConfigSource.default),
    )
