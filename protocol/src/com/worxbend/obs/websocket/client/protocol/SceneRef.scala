package com.worxbend.obs.websocket.client.protocol

import com.worxbend.obs.websocket.client.protocol.requests.*

/** Exactly one validated scene selector. Pass `name` and `uuid` to the generated request fields. */
final class SceneRef private (val name: Field[String], val uuid: Field[String]):
  def setProgram: SetCurrentProgramScene = SetCurrentProgramScene(sceneName = name, sceneUuid = uuid)
  def setPreview: SetCurrentPreviewScene = SetCurrentPreviewScene(sceneName = name, sceneUuid = uuid)
  def items: GetSceneItemList = GetSceneItemList(sceneName = name, sceneUuid = uuid)
  def findItem(sourceName: String, searchOffset: Field[BigDecimal] = Field.Missing): GetSceneItemId =
    GetSceneItemId(sceneName = name, sceneUuid = uuid, sourceName = sourceName, searchOffset = searchOffset)
  def show(item: SceneItemId): SetSceneItemEnabled =
    SetSceneItemEnabled(sceneName = name, sceneUuid = uuid, sceneItemId = item.value, sceneItemEnabled = true)
  def hide(item: SceneItemId): SetSceneItemEnabled =
    SetSceneItemEnabled(sceneName = name, sceneUuid = uuid, sceneItemId = item.value, sceneItemEnabled = false)

object SceneRef:
  def byName(value: String): Either[ProtocolError, SceneRef] =
    if value.trim.isEmpty then Left(ProtocolError("sceneName", "Name must not be blank"))
    else Right(new SceneRef(Field.Value(value), Field.Missing))

  def byUuid(value: String): Either[ProtocolError, SceneRef] =
    if value.trim.isEmpty then Left(ProtocolError("sceneUuid", "UUID must not be blank"))
    else Right(new SceneRef(Field.Missing, Field.Value(value)))
