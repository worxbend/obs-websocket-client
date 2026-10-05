package com.worxbend.obs.websocket.client.transport.okhttp

import com.worxbend.obs.websocket.client.ObsError
import java.net.{InetSocketAddress, Proxy}
import munit.FunSuite
import scala.concurrent.duration.*

class OkHttpClientOptionsSuite extends FunSuite:
  test("defaults validate and render innocuous deadlines with the proxy redacted"):
    val options = OkHttpClientOptions()
    assertEquals(options.validate, Right(options))
    assertEquals(
      options.toString,
      "OkHttpClientOptions(proxy=<redacted>, readTimeout=None, writeTimeout=None, callTimeout=None)",
    )
    val rendering = OkHttpClientOptions(
      proxy        = Some(new java.net.Proxy(java.net.Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 3128))),
      readTimeout  = Some(2.seconds),
      writeTimeout = Some(3.seconds),
      callTimeout  = Some(4.seconds),
    ).toString
    assert(rendering.contains("readTimeout=Some(2 seconds)"))
    assert(rendering.contains("writeTimeout=Some(3 seconds)"))
    assert(rendering.contains("callTimeout=Some(4 seconds)"))
    assert(!rendering.contains("3128"), "Proxy details must stay redacted")

  test("nonpositive deadlines are rejected"):
    val error = Left(ObsError.InvalidConfiguration(message = "OkHttp client deadlines must be positive"))
    assertEquals(OkHttpClientOptions(readTimeout = Some(Duration.Zero)).validate, error)
    assertEquals(OkHttpClientOptions(writeTimeout = Some((-1).second)).validate, error)
    assertEquals(OkHttpClientOptions(callTimeout = Some(Duration.Zero)).validate, error)

  test("build applies the connection deadline and leaves unset knobs to OkHttp"):
    val client = OkHttpClientOptions().build(connectionTimeout = 5.millis)
    assertEquals(client.connectTimeoutMillis, 5)
    assertEquals(client.proxy, null)

  test("build applies proxy and every explicit deadline"):
    val proxy   = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 8080))
    val options = OkHttpClientOptions(
      proxy        = Some(proxy),
      readTimeout  = Some(2.seconds),
      writeTimeout = Some(3.seconds),
      callTimeout  = Some(4.seconds),
    )
    assertEquals(options.validate, Right(options))
    val client = options.build(connectionTimeout = 7.millis)
    assertEquals(client.connectTimeoutMillis, 7)
    assertEquals(client.readTimeoutMillis, 2000)
    assertEquals(client.writeTimeoutMillis, 3000)
    assertEquals(client.callTimeoutMillis, 4000)
    assertEquals(client.proxy, proxy)
