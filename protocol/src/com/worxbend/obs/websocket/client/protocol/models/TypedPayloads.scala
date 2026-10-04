package com.worxbend.obs.websocket.client.protocol.models

import com.worxbend.obs.websocket.client.protocol.*
import com.worxbend.obs.websocket.client.protocol.requests.*
import com.worxbend.obs.websocket.client.protocol.events.{
  InputVolumeMeters,
  SceneItemTransformChanged,
  SceneListChanged
}

/** Opt-in typed nested views. Import `TypedPayloads.*`; existing raw catalog payloads remain source-compatible. */
object TypedPayloads:
  extension (payload: GetCanvasListResponse)
    def typedCanvases: Either[ProtocolError, Vector[Canvas]] =
      ValueCodec.array(Canvas.codec).decode(JsonValue.Arr(payload.canvases), "canvases")

  extension (payload: GetSceneListResponse)
    def typedScenes: Either[ProtocolError, Vector[Scene]] =
      ValueCodec.array(Scene.codec).decode(JsonValue.Arr(payload.scenes), "scenes")

  extension (payload: GetInputListResponse)
    def typedInputs: Either[ProtocolError, Vector[Input]] =
      ValueCodec.array(Input.codec).decode(JsonValue.Arr(payload.inputs), "inputs")

  extension (payload: GetSceneItemListResponse)
    def typedSceneItems: Either[ProtocolError, Vector[SceneItem]] =
      ValueCodec.array(SceneItem.codec).decode(JsonValue.Arr(payload.sceneItems), "sceneItems")

  extension (payload: GetGroupSceneItemListResponse)
    def typedSceneItems: Either[ProtocolError, Vector[SceneItem]] =
      ValueCodec.array(SceneItem.codec).decode(JsonValue.Arr(payload.sceneItems), "sceneItems")

  extension (payload: GetSceneItemTransformResponse)
    def typedSceneItemTransform: Either[ProtocolError, SceneItemTransform] =
      SceneItemTransform.codec.decode(payload.sceneItemTransform, "sceneItemTransform")

  extension (payload: GetMonitorListResponse)
    def typedMonitors: Either[ProtocolError, Vector[Monitor]] =
      ValueCodec.array(Monitor.codec).decode(JsonValue.Arr(payload.monitors), "monitors")

  extension (payload: GetSourceFilterListResponse)
    def typedFilters: Either[ProtocolError, Vector[Filter]] =
      ValueCodec.array(Filter.codec).decode(JsonValue.Arr(payload.filters), "filters")

  extension (payload: GetOutputListResponse)
    def typedOutputs: Either[ProtocolError, Vector[Output]] =
      ValueCodec.array(Output.codec).decode(JsonValue.Arr(payload.outputs), "outputs")

  extension (payload: GetSceneTransitionListResponse)
    def typedTransitions: Either[ProtocolError, Vector[Transition]] =
      ValueCodec.array(Transition.codec).decode(JsonValue.Arr(payload.transitions), "transitions")

  extension (payload: GetInputPropertiesListPropertyItemsResponse)
    def typedPropertyItems: Either[ProtocolError, Vector[PropertyItem]] =
      ValueCodec.array(PropertyItem.codec).decode(JsonValue.Arr(payload.propertyItems), "propertyItems")

  extension (payload: InputVolumeMeters)
    def typedInputs: Either[ProtocolError, Vector[InputVolumeMeter]] =
      ValueCodec.array(InputVolumeMeter.codec).decode(JsonValue.Arr(payload.inputs), "inputs")

  extension (payload: SceneItemTransformChanged)
    def typedSceneItemTransform: Either[ProtocolError, SceneItemTransform] =
      SceneItemTransform.codec.decode(payload.sceneItemTransform, "sceneItemTransform")

  extension (payload: SceneListChanged)
    def typedScenes: Either[ProtocolError, Vector[Scene]] =
      ValueCodec.array(Scene.codec).decode(JsonValue.Arr(payload.scenes), "scenes")
