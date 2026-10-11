# Local HTTP sample

The sample is a separate application; Tapir and Netty are not library dependencies.
It uses Ox, Tapir's synchronous Netty server, jsoniter-scala, and Swagger UI.
Metrics are disabled. The default bind is `127.0.0.1:8080`.

```sh
./mill --no-server server.run
```

- `GET /health` checks HTTP liveness, without connecting to OBS.
- `GET /obs/version` opens a scoped connection, reuses its immutable startup `GetVersion` discovery, and closes it.
- `/docs/` serves Swagger UI, including the read-only endpoint.
- `/docs/docs.yaml` serves the OpenAPI specification.

Typed PureConfig settings are loaded once from `resources/application.conf`.
`HTTP_HOST` and `HTTP_PORT` override the HTTP bind. Set `OBS_WS_URL` (default `ws://localhost:4455`) and `OBS_WS_PASSWORD` in the
process environment. The HTTP API accepts no credentials or target addresses.
Expected OBS failures produce a generic JSON error with HTTP 503; upstream
messages and credentials are not included. This demonstration intentionally
opens one OBS connection per version request and is not a shared session service.

## Operation budget and cleanup

The version service validates configuration before allocating a runtime or starting
its clock. `ObsConfig.requestTimeout` is its total operation budget, including
owned JDK client construction, TCP/WebSocket upgrade, Hello/Identify, startup
capability discovery and version decoding. It sends only the startup `GetVersion`
request and reuses the generation's immutable discovery payload.

The owned acquisition factory recomputes the remaining monotonic budget after
client construction and clips the finite native upgrade deadline to the smaller
of that remainder and `connectionTimeout`. sttp's JDK WebSocket implementation
ignores request `readTimeout`; a non-owning view of the constructed client supplies
the clipped `connectTimeout` that sttp copies into its native WebSocket builder.
The same underlying client retains its proxy/TLS settings and ownership.
After acquisition the factory recomputes the
remainder again and clips handshake/request stage limits to their configured
values. An outer timeout clips interruptible stages (including discovery after
a slow handshake) to the original total deadline, not a fresh per-stage budget.
The injected-backend API and its ownership/deadline contract are unchanged.

Cleanup is separate: `shutdownTimeout` is the bounded graceful WebSocket Close
grace, followed by forced physical release and worker joining. Ownership shielding
ensures even a late acquired socket is registered for cleanup before cancellation
is unmasked. Operation timeout does not discard resources or abandon cleanup.
This is **not an unconditional hard wall-clock bound**: cancellation-ignoring JDK
client construction/shutdown or foreign teardown can delay return beyond the
operation budget plus graceful Close grace. All cleanup remains scoped and joined.

The local pre-upgrade-stall regression uses a shorter total budget than the native
configured connection timeout, checks elapsed completion with timing tolerance,
and observes TCP EOF to verify physical resource release.

## Starter provenance

The starter was obtained from Adopt Tapir with `OxStack`, `Netty`, `Jsoniter`,
Scala 3, documentation enabled and metrics disabled. The service supports sbt
and Scala CLI builders, so a temporary sbt archive was generated, inspected, and
migrated into this repository's sole Mill build. See `generator-provenance.json`.

See [the configuration guide](../docs/server.md) for HOCON, validation and redaction.
