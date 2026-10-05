package com.worxbend.obs.websocket.client.transport

import java.net.{InetSocketAddress, ProxySelector}
import javax.net.ssl.SSLContext
import munit.FunSuite
import scala.concurrent.duration.*

class JdkClientOptionsSuite extends FunSuite:
  test("private JDK client applies proxy trust context and precise connection deadline"):
    val proxy   = ProxySelector.of(new InetSocketAddress("127.0.0.1", 8080))
    val tls     = SSLContext.getDefault
    val options = JdkClientOptions(proxy = Some(proxy), sslContext = Some(tls))
    val client  = options.build(connectionTimeout = 1.nanosecond)
    try
      assertEquals(client.proxy().get(), proxy)
      assertEquals(client.sslContext(), tls)
      assertEquals(client.connectTimeout().get(), java.time.Duration.ofNanos(1))
    finally client.shutdownNow()

  test("defaults build a direct client with the platform TLS context"):
    val client = JdkClientOptions().build(connectionTimeout = 5.millis)
    try
      assertEquals(client.proxy().isEmpty, true)
      assertEquals(client.connectTimeout().get(), java.time.Duration.ofMillis(5))
    finally client.shutdownNow()

  test("rendering redacts sensitive identities"):
    val rendering = JdkClientOptions(
      proxy      = Some(ProxySelector.of(new InetSocketAddress("127.0.0.1", 8080))),
      sslContext = Some(SSLContext.getDefault),
    ).toString
    assertEquals(rendering, "JdkClientOptions(proxy=<redacted>, sslContext=<redacted>)")
    assert(!rendering.contains("8080"))
