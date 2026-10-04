package com.worxbend.obs.websocket.client

import com.worxbend.obs.websocket.client.protocol.Event

/** Latest value per key in a sampling interval. Eviction drops the least recently updated key. */
final case class EventWindow[K](values: Vector[(K, Event)] = Vector.empty, coalesced: Long = 0L, evicted: Long = 0L):
  private[client] def add(key: K, event: Event, maxKeys: Int): EventWindow[K] =
    val remaining = values.filterNot(_._1 == key)
    if remaining.size < values.size then copy(values = remaining :+ (key -> event), coalesced = coalesced + 1)
    else if remaining.size == maxKeys then copy(values = remaining.tail :+ (key -> event), evicted = evicted + 1)
    else copy(values = remaining :+ (key -> event))
