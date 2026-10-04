package com.worxbend.obs.websocket.client.protocol

import java.nio.charset.StandardCharsets.{UTF_8, US_ASCII}
import java.security.MessageDigest
import java.util.Arrays
import java.util.Base64

object Authentication:
  /** OBS v5 challenge response. Hash inputs live in byte arrays that are wiped before returning, so no intermediate
    * secret is retained or logged. The caller keeps ownership of `password` and wipes it after the call.
    */
  def compute(password: Array[Byte], salt: String, challenge: String): String =
    val digest = MessageDigest.getInstance("SHA-256")
    val intermediate = base64Digest(digest, concat(password, salt.getBytes(UTF_8)))
    try
      val response = base64Digest(digest, concat(intermediate, challenge.getBytes(UTF_8)))
      try new String(response, US_ASCII)
      finally Arrays.fill(response, 0.toByte)
    finally Arrays.fill(intermediate, 0.toByte)

  /** Hashes and Base64-encodes the input, then wipes both the input buffer and the raw digest. */
  private def base64Digest(digest: MessageDigest, input: Array[Byte]): Array[Byte] =
    val hashed =
      try digest.digest(input)
      finally Arrays.fill(input, 0.toByte)
    try Base64.getEncoder.encode(hashed)
    finally Arrays.fill(hashed, 0.toByte)

  private def concat(left: Array[Byte], right: Array[Byte]): Array[Byte] =
    val combined = Array.ofDim[Byte](left.length + right.length)
    System.arraycopy(left, 0, combined, 0, left.length)
    System.arraycopy(right, 0, combined, left.length, right.length)
    combined
