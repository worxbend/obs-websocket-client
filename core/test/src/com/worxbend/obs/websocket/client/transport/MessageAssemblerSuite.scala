package com.worxbend.obs.websocket.client.transport

import com.worxbend.obs.websocket.client.ObsError
import munit.FunSuite

class MessageAssemblerSuite extends FunSuite:
  private val incomingSizeLimitMessage: String = "Incoming message exceeds configured byte limit"

  test("a single final fragment completes immediately"):
    val assembler = new MessageAssembler(maxMessageBytes = 16)
    assertEquals(assembler.appendFragment(payload = "{}", finalFragment = true), Right(Some("{}")))

  test("fragments accumulate until the final fragment"):
    val assembler = new MessageAssembler(maxMessageBytes = 16)
    assertEquals(assembler.appendFragment(payload = "{", finalFragment = false), Right(None))
    assertEquals(assembler.appendFragment(payload = "}", finalFragment = true), Right(Some("{}")))

  test("the first fragment can already exceed the byte limit"):
    val assembler = new MessageAssembler(maxMessageBytes = 1)
    assertEquals(
      assembler.appendFragment(payload = "é", finalFragment = false),
      Left(ObsError.MessageTooLarge(message = incomingSizeLimitMessage)),
    )

  test("a later fragment that crosses the byte limit fails the whole message"):
    val assembler = new MessageAssembler(maxMessageBytes = 3)
    assertEquals(assembler.appendFragment(payload = "é", finalFragment = false), Right(None))
    assertEquals(
      assembler.appendFragment(payload = "é", finalFragment = true),
      Left(ObsError.MessageTooLarge(message = incomingSizeLimitMessage)),
    )

  test("multi-byte text at the exact byte limit is accepted"):
    val assembler = new MessageAssembler(maxMessageBytes = 2)
    assertEquals(assembler.appendFragment(payload = "é", finalFragment = true), Right(Some("é")))
