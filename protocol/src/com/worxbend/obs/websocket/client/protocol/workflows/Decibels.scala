package com.worxbend.obs.websocket.client.protocol.workflows

import com.worxbend.obs.websocket.client.protocol.*
import com.worxbend.obs.websocket.client.protocol.requests.SetInputVolume

/** OBS input volume in dB, restricted to the protocol's inclusive [-100, 26] range. */
final class Decibels private (val value: BigDecimal):
  def multiplier: VolumeMultiplier         = VolumeMultiplier.fromDecibels(value = this)
  def set(input: InputRef): SetInputVolume =
    SetInputVolume(inputName = input.name, inputUuid = input.uuid, inputVolumeDb = Field.Value(value = value))

object Decibels:
  def apply(value: BigDecimal): Either[ProtocolError, Decibels] =
    if value < -100 || value > 26 then
      Left(ProtocolError(path = "inputVolumeDb", message = "Must be between -100 and 26"))
    else Right(new Decibels(value = value))
