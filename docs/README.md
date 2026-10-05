# OBS WebSocket Client

A Scala 3 library for scoped, typed control of OBS Studio. Built with Java 25 virtual threads, Ox, sttp, jsoniter-scala, and Mill.

The library is under active development and has no published release. Its
[current architecture](architecture.md) and [decision records](decisions.md)
describe the implemented design.

- [Getting started](quickstart.md)
- [Connect to OBS and read version and scenes](guides/read-version-and-scenes.md)
- [Current architecture and diagrams](architecture.md)
- [Architecture decisions](decisions.md)
- [Peer comparison and feature expansion](feature-expansion.md)
- [Requests and failures](requests.md)
- [Events and ownership](events.md)
- [Recipes](recipes.md)
- [Protocol compatibility](compatibility.md)
- [HTTP sample](server.md)
- [Contributing](contributing.md)
- [Releases](releases.md)

The local microsite build includes API documentation for protocol, core, and every backend adapter (sttp, okhttp, zio, fs2, pekko). Its intended deployment target is `https://worxbend.github.io/obs-websocket-client/`; this address is not a claim of deployment.
