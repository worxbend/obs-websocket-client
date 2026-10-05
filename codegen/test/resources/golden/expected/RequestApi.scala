// Generated from the pinned OBS schema (sha256: fbbeded637bee2326c3f29f045480d8622adab5c4872d13c056f73513fdde881). Do not edit.
package com.worxbend.obs.websocket.client.protocol

/** Discoverable categories for the full pinned catalog. Implementations retain ownership of request policy. */
trait RequestApi[E]:
  def request[A](request: Request[A]): Either[E, A]
  val configuration: ConfigurationApi[E] = new ConfigurationApi(this)
  val general: GeneralApi[E] = new GeneralApi(this)

final class ConfigurationApi[E] private[protocol] (executor: RequestApi[E]):
  /** Executes [[com.worxbend.obs.websocket.client.protocol.requests.Example]] using the owning request executor. */
  def example(`payloadRequestType`: String, `parent.child`: Field[Boolean] = Field.Missing, `slot`: Option[JsonObject]): Either[E, requests.ExampleResponse] =
    executor.request(requests.Example(`payloadRequestType`, `parent.child`, `slot`))

final class GeneralApi[E] private[protocol] (executor: RequestApi[E]):
  /** Executes [[com.worxbend.obs.websocket.client.protocol.requests.Empty]] using the owning request executor. */
  def empty(): Either[E, requests.EmptyResponse] =
    executor.request(requests.Empty())
