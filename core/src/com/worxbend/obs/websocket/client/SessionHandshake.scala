package com.worxbend.obs.websocket.client

import com.worxbend.obs.websocket.client.protocol.*
import com.worxbend.obs.websocket.client.SessionWire.malformed

/** Pure handshake rules: Hello -> Identify, Identified RPC check, close-code meaning. No state; SessionLogic owns it. */
private[client] object SessionHandshake:
  /** Validate Hello and build Identify. Right carries the server's obs-websocket version plus the Identify message. */
  def identify(
    hello:         JsonObject,
    password:      Option[String],
    subscriptions: EventSubscriptions,
  ): Either[ObsError, (String, WireMessage)] =
    for
      rpc     <- hello.int(name = "rpcVersion").left.map(malformed)
      version <- hello.string(name = "obsWebSocketVersion").left.map(malformed)
      _       <- if rpc >= 1 then Right(()) else Left(ObsError.IncompatibleProtocol(version = rpc))
      auth    <- hello.fields.get("authentication") match
                case None                   => Right(Map.empty[String, JsonValue])
                case Some(auth: JsonObject) =>
                  for
                    salt      <- auth.string(name = "salt").left.map(malformed)
                    challenge <- auth.string(name = "challenge").left.map(malformed)
                    password  <- password.toRight(ObsError.Authentication(message = "Server requires a password"))
                  yield Map(
                    "authentication" -> JsonValue.Str(value =
                      authenticationResponse(password = password, salt = salt, challenge = challenge)
                    )
                  )
                case _ =>
                  Left(ObsError.MalformedPayload(path = "authentication", message = "Expected authentication object"))
    yield version -> WireMessage(
      op   = SessionWire.Op.Identify,
      data = JsonObject(
        fields = auth ++ Map(
          "rpcVersion"         -> JsonValue.Num(value = BigDecimal(1)),
          "eventSubscriptions" -> JsonValue.Num(value = BigDecimal(subscriptions.value)),
        )
      ),
    )

  /** The password String cannot be wiped, but its byte copy and every downstream hash buffer can. */
  private def authenticationResponse(password: String, salt: String, challenge: String): String =
    val passwordBytes = password.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    try Authentication.compute(password = passwordBytes, salt = salt, challenge = challenge)
    finally java.util.Arrays.fill(passwordBytes, 0.toByte)

  /** Identified must carry RPC 1, the only version the client requests. */
  def negotiatedRpc(data: JsonObject): Either[ObsError, Int] =
    data
      .int(name = "negotiatedRpcVersion")
      .left
      .map(malformed)
      .flatMap: rpc =>
        if rpc == 1 then Right(rpc) else Left(ObsError.IncompatibleProtocol(version = rpc))

  /** obs-websocket close codes with a typed meaning are only legitimate before the session is ready: during Identify
    * the server rejects with 4009 (AuthenticationFailed), 4010 (UnsupportedRpcVersion — the client always requests RPC
    * version 1) or 4011 (SessionInvalidated). Once ready, the same codes keep their transport classification so retry
    * logic can still treat them as transient.
    */
  def classifyClose(error: ObsError, phase: ConnectionState): ObsError = error match
    case ObsError.Transport(_, Some(code)) if phase != ConnectionState.Ready =>
      code match
        case 4009 => ObsError.Authentication(message = "Server rejected authentication", closeCode = Some(4009))
        case 4010 => ObsError.IncompatibleProtocol(version = 1)
        case 4011 => ObsError.Authentication(message = "Session invalidated by the server", closeCode = Some(4011))
        case _    => error
    case other => other
