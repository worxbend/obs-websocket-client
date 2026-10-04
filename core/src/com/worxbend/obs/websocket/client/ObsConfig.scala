package com.worxbend.obs.websocket.client

import java.net.URI
import scala.concurrent.duration.*
import com.worxbend.obs.websocket.client.protocol.Protocol
import com.worxbend.obs.websocket.client.protocol.enums.EventSubscription
import ox.either.catching

/** Supplies credentials at connection time. Rendering never evaluates or exposes the secret. */
trait PasswordProvider:
  def password(): Either[ObsError, Option[String]]
  final override def toString: String = "PasswordProvider(<redacted>)"

object PasswordProvider:
  val none: PasswordProvider = fixed(None)
  def fixed(value: Option[String]): PasswordProvider = new PasswordProvider:
    def password(): Either[ObsError, Option[String]] = Right(value)

/** OBS's normal-volume event categories; meter subscriptions must be explicit. */
final case class EventSubscriptions private (value: Long)
object EventSubscriptions:
  val none: EventSubscriptions = new EventSubscriptions(0L)

  /** Every generated normal-volume category bit; tracks schema upgrades automatically. */
  val normal: EventSubscriptions = new EventSubscriptions(EventSubscription.All.value)
  def fromLong(value: Long): Either[ObsError, EventSubscriptions] =
    if value >= 0 then Right(new EventSubscriptions(value))
    else Left(ObsError.InvalidConfiguration("Event subscription mask must be nonnegative"))

final case class ObsConfig(
    uri: String = "ws://localhost:4455",
    passwordProvider: PasswordProvider = PasswordProvider.none,
    connectionTimeout: FiniteDuration = 10.seconds,
    handshakeTimeout: FiniteDuration = 10.seconds,
    requestTimeout: FiniteDuration = 10.seconds,
    shutdownTimeout: FiniteDuration = 3.seconds,
    maxInFlight: Int = 256,
    outgoingCapacity: Int = 256,
    subscriptionCapacity: Int = 128,
    maxMessageBytes: Int = Protocol.defaultMaxBytes,
    eventSubscriptions: EventSubscriptions = EventSubscriptions.normal
):
  def validate: Either[ObsError, ObsConfig] =
    URI
      .create(uri)
      .catching[IllegalArgumentException]
      .left
      .map(_ => ObsError.InvalidConfiguration("Invalid WebSocket URI"))
      .flatMap: parsed =>
        if !Set("ws", "wss").contains(parsed.getScheme) || Option(parsed.getHost).isEmpty ||
          Option(parsed.getUserInfo).nonEmpty || Option(parsed.getFragment).nonEmpty
        then Left(ObsError.InvalidConfiguration("Expected ws/wss URI with host, without credentials or fragment"))
        else if parsed.getPort != -1 && (parsed.getPort < 1 || parsed.getPort > 65535) then
          Left(ObsError.InvalidConfiguration("Explicit WebSocket port must be between 1 and 65535"))
        else if List(connectionTimeout, handshakeTimeout, requestTimeout, shutdownTimeout).exists(_ <= Duration.Zero)
        then Left(ObsError.InvalidConfiguration("All deadlines must be positive"))
        else if List(maxInFlight, outgoingCapacity, subscriptionCapacity, maxMessageBytes).exists(_ <= 0) then
          Left(ObsError.InvalidConfiguration("All buffer and message limits must be positive"))
        else Right(this)

  override def toString: String =
    s"ObsConfig(uri=<redacted>, passwordProvider=<redacted>, maxInFlight=$maxInFlight, outgoingCapacity=$outgoingCapacity)"
