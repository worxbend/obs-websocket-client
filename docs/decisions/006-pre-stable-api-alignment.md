# ADR-006: Align pre-stable read and reconnect APIs, no deprecation window

Date: 2026-10-23

Status: Accepted.

## Context

No Maven artifact published yet (0.1.0, 0.2.0: source tags only). No stable source or
binary baseline promised. Audit found public API gaps left by earlier refactors.

- 0.1.0 moved subscription `next()` reads to tri-state `Next[A]`. Diagnostics missed:
  `ObsDiagnosticsSubscription.next()` still returned `Either[ObsError, SessionDiagnostic]`,
  with `Left(ObsError.Closed)` meaning clean end. A private adapter mapped it to `Next` for
  `flow`. No example, server or doc snippet calls diagnostics `next()`.
- Reconnect entrypoints drifted. sttp and okhttp: one `run` with every option defaulted.
  zio and fs2: three overloads (5-arg "compatibility", 3-arg `(config, policy,
  clientOptions)`, 6-arg with no defaults).

## Decision

- `ObsDiagnosticsSubscription.next()` returns `Next[SessionDiagnostic]`, same as event and
  sampled subscriptions. `flow` reads it directly; private adapter deleted.
- `ZioReconnectingObsClient.run` and `Fs2ReconnectingObsClient.run`: one overload,
  `clientOptions: JdkClientOptions = JdkClientOptions()` last and defaulted, same as sttp.
  5-arg and 3-arg overloads removed. Pekko keeps its signature: no `JdkClientOptions`,
  Pekko-native backend.
- 3-arg overload not kept as deprecated. Overload resolution prefers an alternative that
  needs no defaults, so named `run(config, policy, clientOptions = c)` would bind to the
  deprecated overload and warn (fatal under `-Werror`). Checked by compiling zio tests.
- Diagnostics break in place. Scala cannot overload on return type, so a deprecated shim needs a new
  name; that name would outlive its only purpose. Zero published consumers.

## Migration

| Diagnostics before | After |
| --- | --- |
| `Right(d)` | `Next.Item(d)` |
| `Left(ObsError.Closed)` | `Next.Ended` |
| `Left(error)` | `Next.Failed(error)` |

`flow` unchanged.

Reconnect (zio, fs2): named and 5-arg positional calls compile unchanged. Positional
`run(config, policy, clientOptions)` fails to compile (`JdkClientOptions` is not
`ReconnectTiming`); name it: `run(config, policy, clientOptions = c)`. Binary signatures
change; recompile.

## Consequences

One read contract for every subscription; one reconnect `run` shape across JDK-backed
adapters. Source-breaking for diagnostics `next()` callers and positional 3-arg zio/fs2
reconnect calls; recompile. Recorded in CHANGELOG Unreleased.
