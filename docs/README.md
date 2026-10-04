# OBS WebSocket Client

A Scala 3 library for scoped, typed control of OBS Studio. Built with Java 25 virtual threads, Ox, sttp, jsoniter-scala, and Mill.

The library is under active implementation and has no published release. Measured verification and open gates are recorded in [the implementation report](../IMPLEMENTATION.md). The exact scope remains in [PLAN.md](../PLAN.md).

- [Getting started](quickstart.md)
- [Requests and failures](requests.md)
- [Events and ownership](events.md)
- [Recipes](recipes.md)
- [Protocol compatibility](compatibility.md)
- [HTTP sample](server.md)
- [Contributing](contributing.md)
- [Releases](releases.md)

The local microsite build includes API documentation for protocol, core, and sttp. Its intended deployment target is `https://worxbend.github.io/obs-websocket-client/`; this address is not a claim of deployment.
