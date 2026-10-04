# HTTP sample

The separate `server` application uses Tapir, Netty's synchronous server, Ox,
jsoniter-scala, and PureConfig. These dependencies are outside the published
client modules. Swagger UI is enabled; metrics are disabled.

```sh
./mill --no-server server.run
```

The default bind is `127.0.0.1:8080`. `GET /health` checks HTTP liveness without
contacting OBS. `GET /obs/version` opens a scoped OBS connection, discovers and
reads version information, then closes it. Expected OBS failures return a
redacted JSON error with HTTP 503. The HTTP API accepts no target address or
password parameters and exposes no mutating operations.

Swagger UI is served at `/docs/`, its OpenAPI document at `/docs/docs.yaml`.
Tests exercise HTML, JavaScript, CSS, OpenAPI, the live entrypoint's cancellation,
and version discovery against an independent local WebSocket peer.

## Configuration

`server/resources/application.conf` supplies conventional HOCON configuration:

```hocon
http {
  host = "127.0.0.1"
  host = ${?HTTP_HOST}
  port = 8080
  port = ${?HTTP_PORT}
}
obs {
  url = "ws://localhost:4455"
  url = ${?OBS_WS_URL}
  password = ${?OBS_WS_PASSWORD}
}
```

PureConfig loads typed settings once at startup. A nonempty host, a port in
`0..65535` (0 lets the OS assign), and a valid `ws`/`wss` OBS endpoint are
required. The library retains
its explicit `ObsConfig` API; it does not depend on PureConfig or read HOCON.
Passwords use a `Sensitive` wrapper, configuration rendering shows the OBS URL but
masks the password as `<set>` or `<unset>`, and startup errors do not echo
configuration source text.

The four environment overrides above are optional. `OBS_WS_PASSWORD` can be
omitted for an OBS instance without authentication. PureConfig's normal
`application.conf`, system property and external configuration source precedence
applies. A configured host other than loopback changes who can reach this sample.

This demonstration opens one connection per version request. It is a local
read-only integration example, not a shared-session service.

## Starter provenance

Adopt Tapir generated a temporary starter with `OxStack`, `Netty`, `Jsoniter`,
Scala 3, documentation enabled and metrics disabled. Its supported builders
were sbt and Scala CLI, so the sbt archive was inspected and migrated to the sole
Mill build. `server/generator-provenance.json` records settings and archive hash.

Set `HTTP_PORT=0` to let the OS assign a free listening port; the startup message reports the actual port.
Passing a HOCON file path as the first argument loads configuration from that file instead of the default source.
