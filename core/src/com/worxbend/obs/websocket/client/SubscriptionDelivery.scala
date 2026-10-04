package com.worxbend.obs.websocket.client

import com.worxbend.obs.websocket.client.protocol.Event
import ox.channels.ChannelClosed

private[client] object SubscriptionDelivery:
  /** Consumer progress between overflow detection and replacement can remove the old value first. Both queue operations
    * are explicit dependencies so that those interleavings can be replayed.
    */
  def replaceOldest(remove: () => (Option[Event] | ChannelClosed), offer: () => (Boolean | ChannelClosed)): Long =
    val removed = remove() match
      case Some(_) => 1L
      case _       => 0L
    val missed = offer() match
      case true => 0L
      case _    => 1L
    removed + missed
