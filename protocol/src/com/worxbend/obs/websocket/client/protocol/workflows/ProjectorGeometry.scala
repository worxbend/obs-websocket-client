package com.worxbend.obs.websocket.client.protocol.workflows

import com.worxbend.obs.websocket.client.protocol.ProtocolError
import java.nio.ByteBuffer
import java.util.Base64

/** Validated Qt saveGeometry 3.0 payload for a normal, undecorated projector window. */
final class ProjectorGeometry private (val base64: String)

object ProjectorGeometry:
  /** Coordinates are logical desktop pixels; screenWidth is the selected screen's logical width. */
  def window(
      x: Int,
      y: Int,
      width: Int,
      height: Int,
      screen: Int,
      screenWidth: Int
  ): Either[ProtocolError, ProjectorGeometry] =
    val right = x.toLong + width - 1
    val bottom = y.toLong + height - 1
    if width <= 0 || height <= 0 then Left(ProtocolError("geometry", "Dimensions must be positive"))
    else if screen < 0 || screenWidth <= 0 then
      Left(ProtocolError("screen", "Screen index must be nonnegative and width positive"))
    else if right > Int.MaxValue || bottom > Int.MaxValue then
      Left(ProtocolError("geometry", "Rectangle exceeds signed 32-bit coordinates"))
    else
      val data = ByteBuffer.allocate(66)
      val _ = data.putInt(0x01d9d0cb).putShort(3.toShort).putShort(0.toShort)
      def rectangle(): Unit =
        // QDataStream serializes QRect using inclusive right/bottom coordinates.
        val _ = data.putInt(x).putInt(y).putInt(right.toInt).putInt(bottom.toInt)
      rectangle()
      rectangle()
      val _ = data.putInt(screen).put(0.toByte).put(0.toByte).putInt(screenWidth)
      rectangle()
      Right(new ProjectorGeometry(Base64.getEncoder.encodeToString(data.array())))
