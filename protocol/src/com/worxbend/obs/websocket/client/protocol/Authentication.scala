package com.worxbend.obs.websocket.client.protocol

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Base64

object Authentication:
  /** OBS v5 challenge response. No intermediate secret is retained or logged. */
  def compute(password: String, salt: String, challenge: String): String =
    hash(hash(password + salt) + challenge)

  private def hash(value: String): String =
    Base64.getEncoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8)))
