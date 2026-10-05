package com.worxbend.obs.websocket.client.protocol.workflows

import com.worxbend.obs.websocket.client.protocol.ProtocolError
import com.worxbend.obs.websocket.client.protocol.requests.GetSourceScreenshotResponse
import java.util.Base64
import scala.util.control.Exception.catching

/** Decoded compressed image bytes; decoding does not parse an image or access the filesystem. */
final class Screenshot private (val mediaType: String, val bytes: Vector[Byte])

object Screenshot:
  /** Bounds allocations before decoding. Only base64 image data URIs are accepted. */
  def decode(dataUri: String, maxBytes: Int = 16 * 1024 * 1024): Either[ProtocolError, Screenshot] =
    val separator = dataUri.indexOf(',')
    if maxBytes <= 0 then Left(ProtocolError(path = "maxBytes", message = "Must be positive"))
    else if separator < 0 || separator > 128 then invalid(message = "Expected base64 image data URI")
    else
      val header = dataUri.take(separator)
      if !header.matches("data:image/[A-Za-z0-9.+-]+;base64") then invalid(message = "Expected base64 image data URI")
      else if dataUri.length.toLong - separator - 1 > ((maxBytes.toLong + 2) / 3) * 4 then
        invalid(message = "Image exceeds configured byte limit")
      else
        catching(classOf[IllegalArgumentException])
          .either(Base64.getDecoder.decode(dataUri.substring(separator + 1)))
          .left
          .map(_ => ProtocolError(path = "imageData", message = "Invalid base64 image data"))
          .flatMap: decoded =>
            if decoded.isEmpty then invalid(message = "Image data is empty")
            else if decoded.length > maxBytes then invalid(message = "Image exceeds configured byte limit")
            else Right(new Screenshot(mediaType = header.substring(5, header.length - 7), bytes = decoded.toVector))

  def fromResponse(
    response: GetSourceScreenshotResponse,
    maxBytes: Int = 16 * 1024 * 1024,
  ): Either[ProtocolError, Screenshot] =
    decode(dataUri = response.imageData, maxBytes = maxBytes)

  private def invalid(message: String): Left[ProtocolError, Nothing] = Left(
    ProtocolError(path = "imageData", message = message)
  )
