package com.worxbend.obs.websocket.client.codegen.templates

import com.worxbend.obs.websocket.client.codegen.model.{Documentation, Field}
import com.worxbend.obs.websocket.client.codegen.schema.Provenance
import java.util.Locale

/** Shared Scala file and documentation fragments. Complete blocks carry their trailing newline and their own
  * indentation. Place block substitutions immediately after the margin marker; only inline expressions inherit
  * indentation from a template.
  */
private[codegen] object SourceFragments:
  val base: String = "com.worxbend.obs.websocket.client.protocol"

  /** Collapses upstream Markdown descriptions onto one deterministic Scaladoc line. */
  def collapse(description: String): String =
    description.trim.replaceAll("\\s+", " ").replace("*/", "* /")

  private def anchor(name: String): String = name.toLowerCase(Locale.ROOT)

  /** One-or-two-line Scaladoc with the upstream reference for the pinned revision. */
  def documentation(
      doc: Documentation,
      name: String,
      provenance: Provenance,
      fields: List[Field]
  ): String =
    val versions = List(
      Option.when(doc.initialVersion.nonEmpty)(s"initial version ${doc.initialVersion}"),
      Option.when(doc.rpcVersion.nonEmpty)(s"RPC version ${doc.rpcVersion}")
    ).flatten
    val since = if versions.nonEmpty then versions.mkString(" (", ", ", ")") else ""
    val deprecation = if doc.deprecated then " Deprecated upstream." else ""
    val title = if doc.summary.nonEmpty then collapse(doc.summary) else s"Generated binding for $name."
    s"""/** $title
       |  *
       |  * Upstream: [[${provenance.upstreamDocs}#${anchor(name)} $name]]$since.$deprecation
       |${parameterDocs(fields)}  */
       |""".stripMargin

  private def parameterDocs(fields: List[Field]): String =
    fields.map(field => s"  * @param `${field.identifier}` ${collapse(field.description)}\n").mkString

  /** Scaladoc pointing at the owning entry's upstream anchor, with optional field semantics. */
  def seeDoc(
      summary: String,
      name: String,
      anchorTarget: String,
      provenance: Provenance,
      fields: List[Field] = Nil
  ): String =
    s"""/** $summary
       |  *
       |  * Upstream: [[${provenance.upstreamDocs}#${anchor(anchorTarget)} $name]]
       |${parameterDocs(fields)}  */
       |""".stripMargin

  /** File prefix ends with a blank line. Imports are emitted only for payload subpackages. */
  def header(pkg: String, provenance: Provenance, withImport: Boolean): String =
    val imports = if withImport then s"import $base.*\n\n" else ""
    s"""// Generated from the pinned OBS schema (sha256: ${provenance.sha256}). Do not edit.
       |package $pkg
       |
       |$imports""".stripMargin
