package com.worxbend.obs.websocket.client.codegen.schema

/** Reviewed semantic corrections from `protocol-spec/overrides.json`.
  *
  * @param nullableFields
  *   exact wire-field keys such as `GetInputSettings.response.inputSettings`; the middle segment is `request`,
  *   `response`, or `event`. These mark acceptance of explicit JSON null, independently of whether a key may be
  *   omitted. Normalization rejects unmatched keys so misspelled overrides cannot silently change the contract.
  */
final private[codegen] case class Overrides(nullableFields: List[String] = Nil)
