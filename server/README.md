# Local HTTP sample

The sample is a separate application; Tapir and Netty are not library dependencies.
It uses Ox, Tapir's synchronous Netty server, jsoniter-scala, and Swagger UI.
Metrics are disabled. The default bind is `127.0.0.1:8080`.

```sh
./mill --no-server server.run
```

- `GET /health` checks HTTP liveness, without connecting to OBS.
- `GET /obs/version` opens a scoped connection, reads `GetVersion`, and closes it.
- `/docs/` serves Swagger UI, including the read-only endpoint.
- `/docs/docs.yaml` serves the OpenAPI specification.

Typed PureConfig settings are loaded once from `resources/application.conf`.
`HTTP_HOST` and `HTTP_PORT` override the HTTP bind. Set `OBS_WS_URL` (default `ws://localhost:4455`) and `OBS_WS_PASSWORD` in the
process environment. The HTTP API accepts no credentials or target addresses.
Expected OBS failures produce a generic JSON error with HTTP 503; upstream
messages and credentials are not included. This demonstration intentionally
opens one OBS connection per version request and is not a shared session service.

The starter was obtained from Adopt Tapir with `OxStack`, `Netty`, `Jsoniter`,
Scala 3, documentation enabled and metrics disabled. The service supports sbt
and Scala CLI builders, so a temporary sbt archive was generated, inspected, and
migrated into this repository's sole Mill build. See `generator-provenance.json`.

See [the configuration guide](../docs/server.md) for HOCON, validation and redaction.
