package com.worxbend.obs.websocket.client.util

/** Zero-allocation UTF-8 size arithmetic for logical traffic accounting. */
private[client] object Utf8:
  /** The number of bytes `text` occupies when encoded as UTF-8, computed arithmetically without allocating the encoded
    * form: U+0000–U+007F count as 1 byte, U+0080–U+07FF as 2, U+0800–U+FFFF as 3 and a surrogate pair as 4. An unpaired
    * surrogate counts as 3, matching the U+FFFD replacement character a UTF-8 encoder emits in its place.
    */
  def encodedLength(text: String): Long =
    var total = 0L
    var index = 0
    while index < text.length do
      val current = text.charAt(index)
      if current <= 0x7f then total += 1
      else if current <= 0x7ff then total += 2
      else if Character.isHighSurrogate(current) && index + 1 < text.length && Character.isLowSurrogate(
          text.charAt(index + 1)
        )
      then
        total += 4
        index += 1
      else total += 3
      index += 1
    total
