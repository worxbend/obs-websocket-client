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

## Decision

- `ObsDiagnosticsSubscription.next()` returns `Next[SessionDiagnostic]`, same as event and
  sampled subscriptions. `flow` reads it directly; private adapter deleted.
- Break in place. Scala cannot overload on return type, so a deprecated shim needs a new
  name; that name would outlive its only purpose. Zero published consumers.

## Migration

| Before | After |
| --- | --- |
| `Right(d)` | `Next.Item(d)` |
| `Left(ObsError.Closed)` | `Next.Ended` |
| `Left(error)` | `Next.Failed(error)` |

`flow` unchanged.

## Consequences

One read contract for every subscription. Source-breaking for diagnostics `next()`
callers; recompile and match on `Next`. Recorded in CHANGELOG Unreleased.
