# Pinned OBS protocol catalog

`protocol.json` is the official generated OBS WebSocket schema at revision
`073a294cd0646fa1744b0ebf3293bfa63a869b50`. `provenance.json` records its SHA-256,
source URL, catalog counts, and retrieval date. Normal builds use only this
committed input. The upstream license is preserved in `LICENSE.upstream`;
this third-party input is not relicensed under the project's MIT license.

The normalized generator covers all 147 requests, 60 events, and seven enum
groups. Every request has a typed request/response pair and structural codecs;
every event has a model, codec and dispatch entry. Unknown event names retain
their object payload. Enum wrappers retain unknown numeric or string values.

The official schema provides no nested member definitions for `Object` fields.
Those retain `JsonObject`, including unknown members, rather than inventing
unverified shapes. `Number` uses exact `BigDecimal`; OBS validates the documented
numeric restrictions. JSON decoding uses unlimited decimal precision within bounded
308-digit mantissas and scales below 6,178; excessive values are rejected rather
than rounded. Inventory output retains those restrictions and initial
versions. Primitive identifiers in generated wire models are intentional; core
configuration and request IDs add domain validation at the client boundary.

`overrides.json` explicitly lists reviewed nullable fields. Required nullable
fields use `Option` (null is accepted, missing is rejected). Optional fields use
`Field.Missing`, `Field.Null`, and `Field.Value`; request decoding rejects null
for fields which are not documented nullable. Encoding reflects the explicitly
constructed request, so callers should avoid `Field.Null` where unsupported.

Generated Scala and `catalog-inventory.tsv` live in Mill's generated-source
output. Fixtures under `protocol/test/resources` exercise every catalog entry,
every field's missing/null/type behavior, and forward-compatible unknown fields.
These are structural schema tests, not claims of live OBS interoperability.
Real OBS release verification remains deferred until integration gates run.
