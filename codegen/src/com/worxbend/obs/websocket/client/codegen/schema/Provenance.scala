package com.worxbend.obs.websocket.client.codegen.schema

/** Pinned schema provenance. Extra keys in `provenance.json` (retrieval date, counts, license notes) are
  * documentation-only and skipped by the derived codec.
  */
private[codegen] final case class Provenance(repository: String, revision: String, sha256: String):
  def upstreamDocs: String = s"$repository/blob/$revision/docs/generated/protocol.md"
