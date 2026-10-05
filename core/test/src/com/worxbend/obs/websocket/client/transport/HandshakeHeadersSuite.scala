package com.worxbend.obs.websocket.client.transport

import com.worxbend.obs.websocket.client.ObsError
import munit.FunSuite

class HandshakeHeadersSuite extends FunSuite:
  private val testHeaderName: String = "X-Test"

  test("upgrade headers accept HTTP token names and visible ASCII values"):
    val headers = HandshakeHeaders.create(entries = Vector("Authorization" -> "Bearer secret", "X-Trace" -> "\tvalue"))
    assertEquals(headers.toOption.get.entries.size, 2)
    assertEquals(headers.toOption.get.toString, "HandshakeHeaders(<redacted>)")
    assertEquals(HandshakeHeaders.empty.entries, Vector.empty)

  test("upgrade headers reject injection, invalid names, and reserved handshake fields"):
    val invalid = Vector(
      ""                       -> "value",
      "Bad Name"               -> "value",
      testHeaderName           -> "secret\r\nInjected: true",
      testHeaderName           -> "secret\u0000",
      testHeaderName           -> "secret\u007f",
      testHeaderName           -> "é",
      "Connection"             -> "upgrade",
      "Upgrade"                -> "websocket",
      "HOST"                   -> "other.example",
      "Content-Length"         -> "0",
      "Transfer-Encoding"      -> "chunked",
      "Expect"                 -> "100-continue",
      "Sec-WebSocket-Protocol" -> "other",
    )
    invalid.foreach: entry =>
      assertEquals(
        HandshakeHeaders.create(entries = Vector(entry)),
        Left(ObsError.InvalidConfiguration(message = "Invalid or reserved WebSocket handshake header")),
      )
