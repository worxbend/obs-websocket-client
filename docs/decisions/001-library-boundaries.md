# ADR-001: Separate the reusable library from applications

## Status

Accepted; recorded 2026-10-04.

## Date

Recorded 2026-10-04.

## Context

Applications need typed OBS control without taking on an HTTP server, configuration framework, or an effect-system runtime. Protocol decoding must also be testable without opening a network connection. The agreed toolchain is Mill, Scala 3, Java 25, Ox, sttp, and jsoniter-scala.

## Decision

Keep the runtime dependency direction `sttp → core → protocol`. Publish those three library modules separately. `codegen` supplies generated protocol sources at build time. Keep `examples`, the Tapir/Netty `server`, and opt-in integration tests outside the required library graph. Mill remains the only maintained build; public operations use direct-style calls and `Either` for expected errors.

## Alternatives considered

A single artifact containing the HTTP sample would simplify initial wiring but force server dependencies on library consumers. A public effect-polymorphic API would require runtime choices and API concepts outside the agreed scope. A second sbt or Scala CLI build would duplicate toolchain and dependency maintenance. These alternatives conflict with the stated product boundary; this record does not claim they were benchmarked.

## Consequences

The core depends on a small `ObsTransport` interface and can use scripted transports in tests. A consumer can choose the sttp adapter or provide its own transport. HTTP configuration and endpoints evolve independently of the reusable client. More artifacts require explicit packaging and isolated-consumer verification.

## Evidence

See [build.mill](../../build.mill), [ObsTransport.scala](../../core/src/com/worxbend/obs/websocket/client/ObsTransport.scala), and [the architecture overview](../architecture.md). Publication remains subject to the pre-release checklist in [releases](../releases.md).
