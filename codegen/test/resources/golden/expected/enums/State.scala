// Generated from the pinned OBS schema (sha256: fbbeded637bee2326c3f29f045480d8622adab5c4872d13c056f73513fdde881). Do not edit.
package com.worxbend.obs.websocket.client.protocol.enums

/** State values; unknown values are preserved as data.
  *
  * Upstream: [[https://example.com/obs/blob/fixture/docs/generated/protocol.md#state State]]
  */
final case class State(value: String)

/** Published constants for [[State]].
  *
  * Upstream: [[https://example.com/obs/blob/fixture/docs/generated/protocol.md#state State]]
  */
object State:
  val `Ready`: State = State("OBS_READY")
