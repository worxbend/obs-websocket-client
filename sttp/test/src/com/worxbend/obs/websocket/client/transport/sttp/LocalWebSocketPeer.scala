package com.worxbend.obs.websocket.client.transport.sttp

import java.net.{InetAddress, ServerSocket, Socket}
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Base64
import ox.{fork, supervised, timeout}
import scala.concurrent.duration.*

/** Independent minimal RFC6455 peer; exercises the actual sttp/JDK socket path. */
private[client] object LocalWebSocketPeer:
  def run[A](server: Socket => Unit)(client: String => A): A = timeout(8.seconds):
    supervised:
      val listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress)
      try
        val peer = fork:
          val socket = listener.accept()
          socket.setSoTimeout(3000)
          try server(socket)
          finally socket.close()
        val result = client(s"ws://localhost:${listener.getLocalPort}")
        peer.join()
        result
      finally listener.close()

  def upgrade(socket: Socket): Unit =
    val header = readHeaders(socket = socket)
    val key    = header.split("\r\n").find(_.toLowerCase.startsWith("sec-websocket-key:")).get.split(":", 2)(1).trim
    val accept = Base64.getEncoder.encodeToString(
      MessageDigest
        .getInstance("SHA-1")
        .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(UTF_8))
    )
    val response =
      s"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n"
    socket.getOutputStream.write(response.getBytes(UTF_8))
    socket.getOutputStream.flush()

  def readHeaders(socket: Socket): String =
    val input  = socket.getInputStream
    val header = new java.lang.StringBuilder
    while !header.toString.endsWith("\r\n\r\n") do
      val byte = input.read()
      require(byte >= 0 && header.length < 16384, "Invalid handshake")
      val _ = header.append(byte.toChar)
    header.toString

  def send(socket: Socket, text: String, opcode: Int = 1, finalFragment: Boolean = true): Unit =
    val bytes = text.getBytes(UTF_8)
    val out   = socket.getOutputStream
    out.write((if finalFragment then 128 else 0) | opcode)
    if bytes.length < 126 then out.write(bytes.length)
    else
      out.write(126)
      out.write(bytes.length >> 8)
      out.write(bytes.length & 255)
    out.write(bytes)
    out.flush()

  def receive(socket: Socket): (Int, String) =
    val input = socket.getInputStream
    val first = input.read()
    require(first >= 0, "Client closed before frame")
    val second = input.read()
    val length = (second & 127) match
      case 126 => (input.read() << 8) | input.read()
      case 127 => throw new IllegalArgumentException("Test frame too large")
      case n   => n
    val mask    = if (second & 128) != 0 then input.readNBytes(4) else Array.emptyByteArray
    val data    = input.readNBytes(length)
    val decoded = data.indices.map(i => (data(i) ^ (if mask.nonEmpty then mask(i % 4) else 0)).toByte).toArray
    (first & 15, new String(decoded, UTF_8))
