# Getting started

This is an unreleased Scala 3 library for OBS WebSocket 5.x / RPC 1. Java 25 is required. No Maven Central version has been published.

For a complete read-only workflow with a runnable companion, see [Connect to OBS and read version and scenes](guides/read-version-and-scenes.md).

## Prepare OBS

Enable the WebSocket server in OBS Studio's Tools menu and obtain its password. The usual local URL is `ws://localhost:4455`. Use a disposable scene collection for tests. This client never starts recording or streaming during its normal test suite.

## Build locally

```sh
./mill --no-server '{protocol,core,sttp}.publishLocal'
./mill --no-server examples.run
```

The wrapper downloads pinned Mill and Java versions. Set `OBS_WS_PASSWORD` for the example. Pass a URL as an argument to `examples.run` to override localhost. Local publication installs snapshot artifacts in your local Ivy repository; it does not publish a public release. The Maven coordinates shown in this documentation are provisional and not yet published to Maven Central.

## Make a typed request

An equivalent runnable example lives in `examples/src/com/worxbend/obs/websocket/client/examples/Quickstart.scala`; the snippet below is compiled by the documentation build (`tools/doc_snippets.py`).

```scala
import com.worxbend.obs.websocket.client.*
import com.worxbend.obs.websocket.client.protocol.requests.*
import com.worxbend.obs.websocket.client.transport.sttp.SttpObsClient

val config = ObsConfig(
  passwordProvider = PasswordProvider.fixed(sys.env.get("OBS_WS_PASSWORD"))
)
val result = SttpObsClient.connect(config): session =>
  session.request(GetSceneList())
```

The connection callback owns the session. Returning from it closes the socket and joins its workers. Connection errors are the outer `Either`; each operation has its own `Either`. Call `.flatten` when your callback returns one operation's result. An exception thrown from the callback is a defect: it propagates raw after cleanup and is never wrapped in an `ObsError`, while expected failures stay inside the returned `Either`.

## Consume events

```scala
session.withEvents(Set("CurrentProgramSceneChanged")): subscription =>
  subscription.flow.take(1).runForeach(println)
```

This waits for a future matching event. Arrange a deadline in your application when waiting is optional. Subscriptions broadcast independently and have bounded buffers. The default overflow policy fails that subscription; see [Events and ownership](events.md).
