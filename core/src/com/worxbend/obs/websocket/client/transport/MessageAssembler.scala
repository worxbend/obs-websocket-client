package com.worxbend.obs.websocket.client.transport

import com.worxbend.obs.websocket.client.ObsError
import com.worxbend.obs.websocket.client.util.Utf8

/** Aggregates bounded JSON text fragments delivered by a WebSocket backend. UTF-8 size accounting is arithmetic per
  * fragment, so no encoded array is materialized per fragment. Any transport whose backend delivers decoded text
  * fragments — sync or streaming — can reuse one instance per incoming message; control frames and binary frames are
  * the transport's own concern.
  */
final private[client] class MessageAssembler(maxMessageBytes: Int):
  private val text  = new java.lang.StringBuilder
  private var bytes = 0L

  /** Feeds one decoded text fragment. `Right(None)` means more fragments are expected; `Right(Some(message))`
    * completes the aggregation. A fragment that pushes the running total past the byte limit fails the message.
    */
  def appendFragment(payload: String, finalFragment: Boolean): Either[ObsError, Option[String]] =
    val total = bytes + Utf8.encodedLength(text = payload)
    if total > maxMessageBytes then
      Left(ObsError.MessageTooLarge(message = "Incoming message exceeds configured byte limit"))
    else
      bytes = total
      val _ = text.append(payload)
      if finalFragment then Right(Some(text.toString)) else Right(None)
