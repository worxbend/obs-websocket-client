package com.worxbend.obs.websocket.client.protocol.workflows

import com.worxbend.obs.websocket.client.protocol.*
import com.worxbend.obs.websocket.client.protocol.requests.SetInputVolume

/** OBS input linear amplitude, restricted to the protocol's inclusive [0, 20] range. */
final class VolumeMultiplier private (val value: BigDecimal):
  /** Conversion can exceed OBS's dB range; zero represents silence and has no finite dB value. */
  def decibels: Either[ProtocolError, Decibels] =
    if value == 0 then Left(ProtocolError("inputVolumeMul", "Silence has no finite decibel value"))
    else if value < BigDecimal("0.00001") then
      Left(ProtocolError("inputVolumeMul", "Volume is below the supported decibel range"))
    else Decibels(BigDecimal(20 * math.log10(value.toDouble)))

  def set(input: InputRef): SetInputVolume =
    SetInputVolume(inputName = input.name, inputUuid = input.uuid, inputVolumeMul = Field.Value(value))

object VolumeMultiplier:
  def apply(value: BigDecimal): Either[ProtocolError, VolumeMultiplier] =
    if value < 0 || value > 20 then Left(ProtocolError("inputVolumeMul", "Must be between 0 and 20"))
    else Right(new VolumeMultiplier(value))

  private[workflows] def fromDecibels(value: Decibels): VolumeMultiplier =
    new VolumeMultiplier(BigDecimal(math.pow(10, value.value.toDouble / 20)))
