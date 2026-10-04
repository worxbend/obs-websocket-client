# Recipes

These snippets assume a scoped `session`. They compile as documentation examples;
they are not executed by the documentation build. Use actual scene/input names from
OBS and handle the returned `Either` before continuing.

## Switch the program scene

```scala
import com.worxbend.obs.websocket.client.protocol.Field
import com.worxbend.obs.websocket.client.protocol.requests.SetCurrentProgramScene

val result = session.request(SetCurrentProgramScene(sceneName = Field.Value("Camera")))
```

Subscribe to `CurrentProgramSceneChanged` before sending the request if you also
need to observe the resulting event. Another OBS controller can change the scene
again immediately afterward; the request response is not a lasting ownership lock.

## Set microphone volume

```scala
import com.worxbend.obs.websocket.client.protocol.Field
import com.worxbend.obs.websocket.client.protocol.requests.SetInputVolume

val result = session.request(SetInputVolume(
  inputName = Field.Value("Mic/Aux"),
  inputVolumeDb = Field.Value(BigDecimal(-6))
))
```

Choose one of decibels or the linear multiplier. The pinned schema's documented
constraints remain in the catalog; OBS rejects values outside its accepted range.
Exact decimal representation prevents JSON conversion from silently changing the value.

## Read recording status

```scala
import com.worxbend.obs.websocket.client.protocol.requests.GetRecordStatus

val result = session.request(GetRecordStatus())
```

This only reads status. The ordinary test suite never starts streaming or recording.
For an HTTP-facing read-only example, see [the Tapir sample](server.md).
