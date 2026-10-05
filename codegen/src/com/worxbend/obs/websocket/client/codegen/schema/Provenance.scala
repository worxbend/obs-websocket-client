package com.worxbend.obs.websocket.client.codegen.schema

/** Pinned schema provenance. Extra keys in `provenance.json` (retrieval date, counts, license notes) are
  * documentation-only and skipped by the derived codec.
  *
  * @param repository
  *   upstream repository URL used as the base for documentation links
  * @param revision
  *   pinned upstream commit used in those links; normal generation never fetches that commit
  * @param sha256
  *   expected lowercase SHA-256 of the original schema bytes, verified by Generate and emitted in headers
  */
private[codegen] final case class Provenance(repository: String, revision: String, sha256: String):
  /** Documentation URL for this exact schema revision, before a template adds the entry's anchor. */
  def upstreamDocs: String = s"$repository/blob/$revision/docs/generated/protocol.md"
