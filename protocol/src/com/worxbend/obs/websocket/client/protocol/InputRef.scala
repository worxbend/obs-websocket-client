package com.worxbend.obs.websocket.client.protocol

import com.worxbend.obs.websocket.client.protocol.requests.*

/** Exactly one validated input selector. Pass `name` and `uuid` to the generated request fields. */
final class InputRef private (val name: Field[String], val uuid: Field[String]):
  def settings: GetInputSettings = GetInputSettings(inputName = name, inputUuid = uuid)
  def mute: SetInputMute         = SetInputMute(inputName = name, inputUuid = uuid, inputMuted = true)
  def unmute: SetInputMute       = SetInputMute(inputName = name, inputUuid = uuid, inputMuted = false)

object InputRef:
  def byName(value: String): Either[ProtocolError, InputRef] =
    if value.trim.isEmpty then Left(ProtocolError(path = "inputName", message = "Name must not be blank"))
    else Right(new InputRef(name = Field.Value(value = value), uuid = Field.Missing))

  def byUuid(value: String): Either[ProtocolError, InputRef] =
    if value.trim.isEmpty then Left(ProtocolError(path = "inputUuid", message = "UUID must not be blank"))
    else Right(new InputRef(name = Field.Missing, uuid = Field.Value(value = value)))
