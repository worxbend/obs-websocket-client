package com.worxbend.obs.websocket.client.server

class MainSuite extends munit.FunSuite:
  test("swagger URL substitutes loopback for the IPv4 wildcard bind"):
    assertEquals(Main.swaggerUrl("0.0.0.0", 8080), "http://127.0.0.1:8080/docs")

  test("swagger URL substitutes loopback for the IPv6 wildcard bind"):
    assertEquals(Main.swaggerUrl("::", 8080), "http://127.0.0.1:8080/docs")

  test("swagger URL brackets an IPv6 host"):
    assertEquals(Main.swaggerUrl("::1", 8080), "http://[::1]:8080/docs")

  test("swagger URL keeps a routable host unchanged"):
    assertEquals(Main.swaggerUrl("127.0.0.1", 8080), "http://127.0.0.1:8080/docs")

  test("no arguments select the default configuration source"):
    assertEquals(
      Configuration.load(Main.configSource(Array.empty)),
      Configuration.load(pureconfig.ConfigSource.default)
    )
