package com.worxbend.obs.websocket.client.protocol.models

import com.worxbend.obs.websocket.client.protocol.*
import com.worxbend.obs.websocket.client.protocol.requests.*
import com.worxbend.obs.websocket.client.protocol.events.{
  InputVolumeMeters,
  SceneItemTransformChanged,
  SceneListChanged,
}

/** Opt-in typed nested views. Import `TypedPayloads.*`; existing raw catalog payloads remain source-compatible. */
object TypedPayloads:
  extension (payload: GetCanvasListResponse)
    def typedCanvases: Either[ProtocolError, Vector[Canvas]] =
      ValueCodec
        .array(element = Canvas.codec)
        .decode(value = JsonValue.Arr(value = payload.canvases), path = "canvases")

  extension (payload: GetSceneListResponse)
    def typedScenes: Either[ProtocolError, Vector[Scene]] =
      ValueCodec.array(element = Scene.codec).decode(value = JsonValue.Arr(value = payload.scenes), path = "scenes")

  extension (payload: GetInputListResponse)
    def typedInputs: Either[ProtocolError, Vector[Input]] =
      ValueCodec.array(element = Input.codec).decode(value = JsonValue.Arr(value = payload.inputs), path = "inputs")

  extension (payload: GetSceneItemListResponse)
    def typedSceneItems: Either[ProtocolError, Vector[SceneItem]] =
      ValueCodec
        .array(element = SceneItem.codec)
        .decode(value = JsonValue.Arr(value = payload.sceneItems), path = "sceneItems")

  extension (payload: GetGroupSceneItemListResponse)
    def typedSceneItems: Either[ProtocolError, Vector[SceneItem]] =
      ValueCodec
        .array(element = SceneItem.codec)
        .decode(value = JsonValue.Arr(value = payload.sceneItems), path = "sceneItems")

  extension (payload: GetSceneItemTransformResponse)
    def typedSceneItemTransform: Either[ProtocolError, SceneItemTransform] =
      SceneItemTransform.codec.decode(value = payload.sceneItemTransform, path = "sceneItemTransform")

  extension (payload: GetMonitorListResponse)
    def typedMonitors: Either[ProtocolError, Vector[Monitor]] =
      ValueCodec
        .array(element = Monitor.codec)
        .decode(value = JsonValue.Arr(value = payload.monitors), path = "monitors")

  extension (payload: GetSourceFilterListResponse)
    def typedFilters: Either[ProtocolError, Vector[Filter]] =
      ValueCodec.array(element = Filter.codec).decode(value = JsonValue.Arr(value = payload.filters), path = "filters")

  extension (payload: GetOutputListResponse)
    def typedOutputs: Either[ProtocolError, Vector[Output]] =
      ValueCodec.array(element = Output.codec).decode(value = JsonValue.Arr(value = payload.outputs), path = "outputs")

  extension (payload: GetSceneTransitionListResponse)
    def typedTransitions: Either[ProtocolError, Vector[Transition]] =
      ValueCodec
        .array(element = Transition.codec)
        .decode(value = JsonValue.Arr(value = payload.transitions), path = "transitions")

  extension (payload: GetInputPropertiesListPropertyItemsResponse)
    def typedPropertyItems: Either[ProtocolError, Vector[PropertyItem]] =
      ValueCodec
        .array(element = PropertyItem.codec)
        .decode(value = JsonValue.Arr(value = payload.propertyItems), path = "propertyItems")

  extension (payload: InputVolumeMeters)
    def typedInputs: Either[ProtocolError, Vector[InputVolumeMeter]] =
      ValueCodec
        .array(element = InputVolumeMeter.codec)
        .decode(value = JsonValue.Arr(value = payload.inputs), path = "inputs")

  extension (payload: SceneItemTransformChanged)
    def typedSceneItemTransform: Either[ProtocolError, SceneItemTransform] =
      SceneItemTransform.codec.decode(value = payload.sceneItemTransform, path = "sceneItemTransform")

  extension (payload: SceneListChanged)
    def typedScenes: Either[ProtocolError, Vector[Scene]] =
      ValueCodec.array(element = Scene.codec).decode(value = JsonValue.Arr(value = payload.scenes), path = "scenes")
