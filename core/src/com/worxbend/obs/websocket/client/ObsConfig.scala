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
  val none: PasswordProvider                         = fixed(value = None)
  def fixed(value: Option[String]): PasswordProvider = new PasswordProvider:
    def password(): Either[ObsError, Option[String]] = Right(value)

/** OBS's normal-volume event categories; meter subscriptions must be explicit. */
final case class EventSubscriptions private (value: Long)
object EventSubscriptions:
  val none: EventSubscriptions = new EventSubscriptions(value = 0L)

  /** Every generated normal-volume category bit; tracks schema upgrades automatically. */
  val normal: EventSubscriptions = new EventSubscriptions(value = EventSubscription.All.value)
  def fromLong(value: Long): Either[ObsError, EventSubscriptions] =
    if value >= 0 then Right(new EventSubscriptions(value = value))
    else Left(ObsError.InvalidConfiguration(message = "Event subscription mask must be nonnegative"))

final case class ObsConfig(
  uri:                  String = "ws://localhost:4455",
  passwordProvider:     PasswordProvider = PasswordProvider.none,
  connectionTimeout:    FiniteDuration = 10.seconds,
  handshakeTimeout:     FiniteDuration = 10.seconds,
  requestTimeout:       FiniteDuration = 10.seconds,
  shutdownTimeout:      FiniteDuration = 3.seconds,
  maxInFlight:          Int = 256,
  outgoingCapacity:     Int = 256,
  subscriptionCapacity: Int = 128,
  /** Frame byte limit enforced on both directions: the transport rejects oversized outgoing sends with
    * [[ObsError.MessageTooLarge]], and oversized inbound frames fail the session and abort the connection
    * immediately, so a violating peer cannot keep it open.
    */
  maxMessageBytes:    Int = Protocol.defaultMaxBytes,
  eventSubscriptions: EventSubscriptions = EventSubscriptions.normal,
  readiness:          Option[ReadinessPolicy] = None,
):
  def validate: Either[ObsError, ObsConfig] =
    URI
      .create(uri)
      .catching[IllegalArgumentException]
      .left
      .map(_ => ObsError.InvalidConfiguration(message = "Invalid WebSocket URI"))
      .flatMap: parsed =>
        if !hasWebSocketAddress(parsed = parsed) || hasPrivateComponents(parsed = parsed) then
          Left(
            ObsError
              .InvalidConfiguration(message = "Expected ws/wss URI with host, without credentials, query or fragment")
          )
        else if parsed.getPort != -1 && (parsed.getPort < 1 || parsed.getPort > 65535) then
          Left(ObsError.InvalidConfiguration(message = "Explicit WebSocket port must be between 1 and 65535"))
        else if List(connectionTimeout, handshakeTimeout, requestTimeout, shutdownTimeout).exists(_ <= Duration.Zero)
        then Left(ObsError.InvalidConfiguration(message = "All deadlines must be positive"))
        else if List(maxInFlight, outgoingCapacity, subscriptionCapacity, maxMessageBytes).exists(_ <= 0) then
          Left(ObsError.InvalidConfiguration(message = "All buffer and message limits must be positive"))
        else readiness.map(_.validate).getOrElse(Right(())).map(_ => this)

  private def hasWebSocketAddress(parsed: URI): Boolean =
    Set("ws", "wss").contains(parsed.getScheme) && Option(parsed.getHost).nonEmpty

  /** True when a resolved password would authenticate over plaintext `ws` to a non-loopback host: the OBS
    * authentication hash then crosses the network in a form an eavesdropper can replay against that session.
    * Sessions resolve only validated configurations, so unparseable or hostless URIs are treated as unexposed here;
    * validation rejects them before any session starts.
    */
  private[client] def plaintextCredentialsExposed(password: Option[String]): Boolean =
    password.nonEmpty &&
      URI
        .create(uri)
        .catching[IllegalArgumentException]
        .toOption
        .exists: parsed =>
          parsed.getScheme == "ws" && Option(parsed.getHost).exists(host => !ObsConfig.isLoopbackHost(host = host))

  private def hasPrivateComponents(parsed: URI): Boolean =
    Option(parsed.getUserInfo).nonEmpty || Option(parsed.getQuery).nonEmpty || Option(parsed.getFragment).nonEmpty

  /** Validation forbids credentials, queries and fragments, so a valid URI never carries secrets and renders as-is.
    * Unvalidated copies still have those components stripped defensively; passwords never render.
    */
  override def toString: String =
    val safeUri = URI
      .create(uri)
      .catching[IllegalArgumentException]
      .map(parsed =>
        new URI(parsed.getScheme, null, parsed.getHost, parsed.getPort, parsed.getPath, null, null).toString
      )
      .getOrElse("<invalid>")
    s"ObsConfig(uri=$safeUri, passwordProvider=<redacted>, maxInFlight=$maxInFlight, outgoingCapacity=$outgoingCapacity)"

private[client] object ObsConfig:
  /** Loopback spellings recognized without DNS resolution: `localhost`, the 127.0.0.0/8 IPv4 range, and `::1` (URI
    * hosts retain their brackets). Every other host is treated as remote.
    */
  def isLoopbackHost(host: String): Boolean =
    val bare = host.stripPrefix("[").stripSuffix("]")
    bare.equalsIgnoreCase("localhost") || bare == "::1" || isIpv4Loopback(host = bare)

  private def isIpv4Loopback(host: String): Boolean =
    val octets = host.split("\\.")
    octets.length == 4 && octets.head == "127" && octets.forall(isOctet)

  private def isOctet(octet: String): Boolean =
    octet.matches("[0-9]{1,3}") && octet.toInt <= 255
