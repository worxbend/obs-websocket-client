package com.worxbend.obs.websocket.client.util

import munit.FunSuite
import java.nio.charset.StandardCharsets.UTF_8

class Utf8Suite extends FunSuite:
  /** Builds a string from code points, keeping unpaired surrogates intact. */
  private def of(codePoints: Int*): String =
    new String(codePoints.toArray.flatMap(Character.toChars))

  /** The platform encoder is the oracle for well-formed strings. */
  private def check(text: String): Unit =
    assertEquals(Utf8.encodedLength(text = text), text.getBytes(UTF_8).length.toLong)

  test("empty and ASCII strings count one byte per character"):
    assertEquals(Utf8.encodedLength(text = ""), 0L)
    assertEquals(Utf8.encodedLength(text = "a"), 1L)
    check(text = "hello world")
    check(text = "~ !@#$%^&*()_+")

  test("boundary code points count their UTF-8 sequence lengths"):
    check(text = of(0x7f))   // last 1-byte code point
    check(text = of(0x80))   // first 2-byte code point
    check(text = of(0x7ff))  // last 2-byte code point
    check(text = of(0x800))  // first 3-byte code point
    check(text = of(0xffff)) // last BMP code point: 3 bytes
    check(text = of(0x41, 0x20ac, 0x800, 0xffff))

  test("supplementary code points count as surrogate pairs of four bytes"):
    assertEquals(Utf8.encodedLength(text = of(0x1f600)), 4L)
    check(text = of(0x1f600))
    check(text = of(0x61, 0x1f600, 0x62, 0x10ffff))

  test("unpaired surrogates count as the 3-byte U+FFFD replacement character"):
    assertEquals(Utf8.encodedLength(text = of(0xd800)), 3L) // lone high surrogate
    assertEquals(Utf8.encodedLength(text = of(0xdc00)), 3L) // lone low surrogate
    assertEquals(Utf8.encodedLength(text = of(0xd800, 0x78)), 4L) // high surrogate followed by a BMP character
    assertEquals(Utf8.encodedLength(text = of(0x78, 0xd800)), 4L) // high surrogate at the end
    assertEquals(Utf8.encodedLength(text = of(0xd800, 0xd800)), 6L) // consecutive high surrogates
    assertEquals(Utf8.encodedLength(text = of(0xdc00, 0x1f600, 0xd800)), 10L) // low, valid pair, high
