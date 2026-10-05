package com.worxbend.obs.websocket.client.protocol

/** Nonnegative integral scene-item identifier, kept decimal on the wire without narrowing or rounding. */
opaque type SceneItemId = BigDecimal

object SceneItemId:
  def from(value: BigDecimal): Either[ProtocolError, SceneItemId] =
    if value < 0 || !value.isWhole then
      Left(ProtocolError(path = "sceneItemId", message = "Expected a nonnegative integer"))
    else Right(value)

  extension (id: SceneItemId) def value: BigDecimal = id
