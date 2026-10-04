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

## Use a validated input and volume unit

```scala
import com.worxbend.obs.websocket.client.protocol.InputRef
import com.worxbend.obs.websocket.client.protocol.workflows.Decibels

val command = for
  input <- InputRef.byName("Mic/Aux")
  volume <- Decibels(BigDecimal(-6))
yield volume.set(input)
val result = command.map(request => session.request(request))
```

Smart constructors report `ProtocolError` before any network operation. `VolumeMultiplier` supports the protocol's linear range, with amplitude conversion using 20 log10. Silence has no finite decibel value; conversions outside the supported dB range return an error. Scene and input references select exactly one name or UUID. UUID constructors require a nonblank server identifier, rather than assuming every OBS source identifier is a Java UUID.

## Decode a screenshot in memory

```scala
import com.worxbend.obs.websocket.client.protocol.Field
import com.worxbend.obs.websocket.client.protocol.requests.GetSourceScreenshot
import com.worxbend.obs.websocket.client.protocol.workflows.Screenshot

val result = session.request(GetSourceScreenshot(
  sourceName = Field.Value("Camera"), imageFormat = "png"
)).map(response => Screenshot.fromResponse(response, maxBytes = 8 * 1024 * 1024))
```

The helper checks the image data URI, base64 encoding, and decoded byte limit, and returns a media type plus immutable compressed bytes. It does not inspect image pixels or write files. Choose a format reported by `GetVersion` and set the session message limit to accommodate base64 overhead.

## Build browser-source settings

```scala
import com.worxbend.obs.websocket.client.protocol.InputRef
import com.worxbend.obs.websocket.client.protocol.workflows.BrowserInputSettings

val command = for
  input <- InputRef.byName("Overlay")
  page <- BrowserInputSettings.remote("https://example.com/overlay")
  sized <- page.withSize(1920, 1080)
yield sized.shutdownWhenHidden.restartWhenActive.set(input)
val result = command.map(request => session.request(request))
```

Patches use OBS browser-source keys and explicitly request overlay mode, preserving unrelated settings. `local` selects a file on the OBS machine. `withCss`, `withFrameRate`, and lifecycle methods add only their own fields; `mergeInto` preserves unknown settings in an existing raw object. The selected input must be a browser source. See the [upstream setting reader](https://github.com/obsproject/obs-browser/blob/master/obs-browser-source.cpp).

## Place a windowed source projector

```scala
import com.worxbend.obs.websocket.client.protocol.Field
import com.worxbend.obs.websocket.client.protocol.requests.OpenSourceProjector
import com.worxbend.obs.websocket.client.protocol.workflows.ProjectorGeometry

val result = ProjectorGeometry.window(100, 100, 1280, 720, screen = 0, screenWidth = 1920)
  .map(geometry => session.request(OpenSourceProjector(
    sourceName = Field.Value("Camera"), projectorGeometry = Field.Value(geometry.base64)
  )))
```

The helper emits the Qt saveGeometry 3.0 format for a normal undecorated window, with inclusive rectangle edges. Coordinates and screen width use logical desktop pixels; OBS/Qt can adjust the restored position for the actual display setup. This is not a cross-platform pixel-placement guarantee. The format is based on [Qt's geometry serializer](https://github.com/qt/qtbase/blob/6.8/src/widgets/kernel/qwidget.cpp).
