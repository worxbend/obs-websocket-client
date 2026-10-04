# Changelog

## Unreleased

- Add Scala 3 protocol, core, and sttp modules for OBS WebSocket 5.x/RPC 1.
- Generate typed bindings from the pinned 147-request/60-event catalog.
- Add scoped authentication, bounded request/event dispatch, typed batching, raw extensions, and opt-in reconnect.
- Add a read-only Tapir sample with PureConfig/HOCON, Swagger, and local binding.
- Add deterministic fault tests, exact coverage enforcement, source/API artifacts, isolated consumer checks, and documentation tooling.
- Hardening: fix `server.run` Netty lifecycle leak, consolidate the error taxonomy (non-fatal `MessageTooLarge`, `UnsupportedMessage`, `InternalError`), make capability gating and raw escape hatches coherent across single/batch APIs, unify duplicate raw-event types, and harden the generator (override validation, duplicate detection, literal escaping, upstream-linked Scaladoc).

This entry describes local implementation, not a published release. See IMPLEMENTATION.md for measured evidence and remaining gates.
