package com.worxbend.obs.websocket.client.protocol

import com.worxbend.obs.websocket.client.protocol.requests.*

/** Exactly one validated scene selector. Pass `name` and `uuid` to the generated request fields. */
final class SceneRef private (val name: Field[String], val uuid: Field[String]):
  /** Builds a request to select this scene for program output; sending it is the caller's responsibility. */
  def setProgram(): SetCurrentProgramScene = SetCurrentProgramScene(sceneName = name, sceneUuid = uuid)

  /** Builds a request to select this scene for preview output; sending it is the caller's responsibility. */
  def setPreview(): SetCurrentPreviewScene = SetCurrentPreviewScene(sceneName = name, sceneUuid = uuid)
  def items: GetSceneItemList              = GetSceneItemList(sceneName = name, sceneUuid = uuid)
  def findItem(sourceName: String, searchOffset: Field[BigDecimal] = Field.Missing): GetSceneItemId =
    GetSceneItemId(sceneName = name, sceneUuid = uuid, sourceName = sourceName, searchOffset = searchOffset)
  def show(item: SceneItemId): SetSceneItemEnabled =
    SetSceneItemEnabled(sceneName = name, sceneUuid = uuid, sceneItemId = item.value, sceneItemEnabled = true)
  def hide(item: SceneItemId): SetSceneItemEnabled =
    SetSceneItemEnabled(sceneName = name, sceneUuid = uuid, sceneItemId = item.value, sceneItemEnabled = false)

object SceneRef:
  def byName(value: String): Either[ProtocolError, SceneRef] =
    if value.trim.isEmpty then Left(ProtocolError(path = "sceneName", message = "Name must not be blank"))
    else Right(new SceneRef(name = Field.Value(value = value), uuid = Field.Missing))

  def byUuid(value: String): Either[ProtocolError, SceneRef] =
    if value.trim.isEmpty then Left(ProtocolError(path = "sceneUuid", message = "UUID must not be blank"))
    else Right(new SceneRef(name = Field.Missing, uuid = Field.Value(value = value)))
