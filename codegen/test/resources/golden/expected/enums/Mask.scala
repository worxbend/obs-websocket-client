// Generated from the pinned OBS schema (sha256: fbbeded637bee2326c3f29f045480d8622adab5c4872d13c056f73513fdde881). Do not edit.
package com.worxbend.obs.websocket.client.protocol.enums

/** Mask values; unknown values are preserved as data.
  *
  * Upstream: [[https://example.com/obs/blob/fixture/docs/generated/protocol.md#mask Mask]]
  */
final case class Mask(value: Long)

/** Published constants for [[Mask]].
  *
  * Upstream: [[https://example.com/obs/blob/fixture/docs/generated/protocol.md#mask Mask]]
  */
object Mask:
  val `One`: Mask = Mask((1 << 0))
  /** Mask alias. */
  val `All`: Mask = Mask((`One`.value))
