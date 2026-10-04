package com.worxbend.obs.websocket.client.protocol.workflows

import com.worxbend.obs.websocket.client.protocol.*
import com.worxbend.obs.websocket.client.protocol.requests.SetInputVolume

/** OBS input linear amplitude, restricted to the protocol's inclusive [0, 20] range. */
final class VolumeMultiplier private (val value: BigDecimal):
  /** Converts to decibels with 20·log10. Zero represents silence and has no finite dB value; values below 0.00001 are
    * under the -100 dB floor. The documented multiplier ceiling of 20 converts to ≈26.02 dB, marginally above the OBS
    * dB ceiling of 26, so the result saturates at 26 dB: every valid multiplier maps onto a valid dB value instead of
    * failing conversion at the top of its documented range.
    */
  def decibels: Either[ProtocolError, Decibels] =
    if value == 0 then Left(ProtocolError("inputVolumeMul", "Silence has no finite decibel value"))
    else if value < BigDecimal("0.00001") then
      Left(ProtocolError("inputVolumeMul", "Volume is below the supported decibel range"))
    else Decibels(BigDecimal(math.min(20 * math.log10(value.toDouble), 26.0)))

  def set(input: InputRef): SetInputVolume =
    SetInputVolume(inputName = input.name, inputUuid = input.uuid, inputVolumeMul = Field.Value(value))

object VolumeMultiplier:
  def apply(value: BigDecimal): Either[ProtocolError, VolumeMultiplier] =
    if value < 0 || value > 20 then Left(ProtocolError("inputVolumeMul", "Must be between 0 and 20"))
    else Right(new VolumeMultiplier(value))

  private[workflows] def fromDecibels(value: Decibels): VolumeMultiplier =
    new VolumeMultiplier(BigDecimal(math.pow(10, value.value.toDouble / 20)))
