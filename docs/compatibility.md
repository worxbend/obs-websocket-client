# Protocol compatibility

The project targets OBS WebSocket 5.x JSON text and RPC 1. OBS 4.x, MessagePack, Scala 2, Scala.js, and Scala Native are outside scope.

## Catalog provenance

The exact upstream revision, source URLs, schema checksum, and overrides live in [protocol-spec provenance](https://github.com/worxbend/obs-websocket-client/tree/main/protocol-spec). Normal builds generate from this committed schema without fetching a moving branch.

The current catalog contains 147 request types and 60 event types. Generated models are not evidence that every operation has been exercised against OBS. The generated inventory distinguishes coverage evidence and documented schema limits.

## Toolchain

Scala 3.9.0, Mill 1.1.10, Java 25 (Temurin 25.0.3), Ox 1.0.9, sttp 4.0.27, jsoniter-scala 2.41.2, and Tapir 1.13.32 are pinned. The HTTP sample is separate from all published client modules.

## Tested servers

The following local smoke checks ran on 2026-10-04. This matrix records exercised operations; it does not certify every generated request on these servers.

| OBS Studio | obs-websocket | Environment | Executed checks |
| --- | --- | --- | --- |
| 30.2.3 | 5.5.2 | Debian 13 container, private Xvfb, no host mounts | Wrong-password rejection (4009); authenticated discovery; scene create/switch/event/restore/remove; all three tests repeated after stopping and restarting OBS |

Run `tools/real_obs_smoke.sh` with Docker available to reproduce the isolated check. It installs OBS inside a disposable Debian container, uses a generated password and a localhost-only port, writes evidence under `out/real-obs-smoke/`, and removes its container before recording success. The final local run used the official `debian:13-slim` image (`sha256:a99cfc517144bc59b1978475ec53b46ecabec7e43635402ee5b77cc54cd1b20a`); all six test executions passed with no skips. `OBS_DOCKER_IMAGE` can select a Debian-compatible base; evidence records the resolved image and OBS package version. The manual/scheduled GitHub workflow invokes this launcher, but has not yet run remotely.

The restart smoke uses fresh scoped connections. In-flight disconnect races and opt-in reconnect behavior are covered by deterministic tests. The pinned catalog also contains newer fields and operations unavailable in OBS 30.2.3; check discovered request capabilities and the operation's server requirements. No broad OBS 5.x version guarantee is made.

## Upgrade policy

Schema upgrades require reviewing the upstream diff, explicit overrides, deterministic generation, golden fixtures, and the catalog inventory. Source and binary compatibility are not promised before the first stable release.

## Isolation requirement

Use a disposable container with an empty home/configuration directory and no host
OBS configuration mounts for automated live tests. Do not launch the installed
Flatpak with only `XDG_CONFIG_HOME` overrides: Flatpak can replace reserved XDG
paths and cause OBS to load its normal profile. An attempted launch exposed this
behavior during implementation; see the repository's OBS-INTEGRATION-NOTE.md.
