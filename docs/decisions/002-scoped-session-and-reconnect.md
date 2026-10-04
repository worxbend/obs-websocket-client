# ADR-002: Scope sessions and isolate reconnect generations

## Status

Accepted; retrospective record of PLAN sections 8, 11, and 12.

## Date

Recorded 2026-10-04.

## Context

A session coordinates concurrent requests, socket workers, and independent event consumers. A blocked reader must not prevent shutdown. A lost response does not reveal whether OBS executed a mutation, so automatic replay can duplicate side effects.

## Decision

Use callback-scoped connections with Ox ownership. Confine mutable pending-request and subscriber state to `SessionLogic`, reached through an Ox actor. Serialize writes through a bounded channel and broadcast events through separate bounded subscriber queues. Resolve logical pending work and physically close the transport before joining the reader.

Keep reconnect opt-in. Every retry opens a fresh generation after the previous scope ends, resolves credentials again, and reports explicit notices. Preserve the requested server event mask, require the application to recreate local subscriptions, and never transfer or replay pending operations. Retry only errors classified as transient and within the run's retry budget.

## Alternatives considered

Returning a freely escaping session would move worker ownership and cleanup obligations to every caller. One competing-consumer event queue would not deliver each event to every subscriber. Blocking event delivery would allow a slow consumer to stall responses. Transparent replay could repeat a mutation that already succeeded. The selected design makes these boundaries explicit.

## Consequences

Sessions and subscriptions must stay inside their callbacks. A generation callback may run multiple times, so application recovery logic must account for previously completed work. A timeout remains an uncertain outcome for a submitted mutation. Bounded queues expose overload rather than silently retaining an unbounded event history; the application still controls how many subscriptions it creates.

Injected backends remain caller-owned. They must enforce finite upgrade deadlines and provide an individual-connection abort callback. Shielded acquisition can delay cancellation until that backend deadline; this is an explicit trade-off to prevent late-upgrade leaks.

## Evidence

See [ObsClient.scala](../../core/src/com/worxbend/obs/websocket/client/ObsClient.scala), [SessionLogic.scala](../../core/src/com/worxbend/obs/websocket/client/SessionLogic.scala), [SttpObsClient.scala](../../sttp/src/com/worxbend/obs/websocket/client/transport/sttp/SttpObsClient.scala), and [ReconnectingObsClient.scala](../../sttp/src/com/worxbend/obs/websocket/client/transport/sttp/ReconnectingObsClient.scala). The [event guide](../events.md) documents observable ownership and retry behavior.
