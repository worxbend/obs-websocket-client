package com.worxbend.obs.websocket.client.codegen.templates

import com.worxbend.obs.websocket.client.codegen.model.{Documentation, Field}
import com.worxbend.obs.websocket.client.codegen.schema.Provenance
import java.util.Locale

/** Shared Scala file and documentation fragments. Complete blocks carry their trailing newline and their own
  * indentation. Place block substitutions immediately after the margin marker; only inline expressions inherit
  * indentation from a template.
  *
  * For example, a block substituted immediately after `|` in a stripMargin template must already contain all output
  * indentation. Prefixing it with spaces only indents its first line. Header/documentation fragments end in a newline;
  * parameter/expression fragments in FieldFragments do not. These conventions keep the template readable as emitted
  * source and preserve byte-for-byte fixture comparisons.
  *
  * Escaping here protects Scaladoc text and comment delimiters; ScalaLiteral separately escapes executable string
  * literals. Neither kind of escaping should be substituted for the other.
  */
private[codegen] object SourceFragments:
  val base: String = "com.worxbend.obs.websocket.client.protocol"

  /** Collapses upstream Markdown descriptions onto one deterministic Scaladoc line. */
  def collapse(description: String): String =
    description.trim.replaceAll("\\s+", " ").replace("*/", "* /")

  private def anchor(name: String): String = name.toLowerCase(Locale.ROOT)

  /** One-or-two-line Scaladoc with the upstream reference for the pinned revision. */
  def documentation(
    doc:        Documentation,
    name:       String,
    provenance: Provenance,
    fields:     List[Field],
  ): String =
    val versions = List(
      Option.when(doc.initialVersion.nonEmpty)(s"initial version ${doc.initialVersion}"),
      Option.when(doc.rpcVersion.nonEmpty)(s"RPC version ${doc.rpcVersion}"),
    ).flatten
    val since       = if versions.nonEmpty then versions.mkString(" (", ", ", ")") else ""
    val deprecation = if doc.deprecated then " Deprecated upstream." else ""
    val title = if doc.summary.nonEmpty then collapse(description = doc.summary) else s"Generated binding for $name."
    s"""/** $title
       |  *
       |  * Upstream: [[${provenance.upstreamDocs}#${anchor(name = name)} $name]]$since.$deprecation
       |${parameterDocs(fields = fields)}  */
       |""".stripMargin

  private def parameterDocs(fields: List[Field]): String =
    fields.map(field => s"  * @param `${field.identifier}` ${collapse(description = field.description)}\n").mkString

  /** Scaladoc pointing at the owning entry's upstream anchor, with optional field semantics. */
  def seeDoc(
    summary:      String,
    name:         String,
    anchorTarget: String,
    provenance:   Provenance,
    fields:       List[Field] = Nil,
  ): String =
    s"""/** $summary
       |  *
       |  * Upstream: [[${provenance.upstreamDocs}#${anchor(name = anchorTarget)} $name]]
       |${parameterDocs(fields = fields)}  */
       |""".stripMargin

  /** File prefix ends with a blank line. Imports are emitted only for payload subpackages. */
  def header(pkg: String, provenance: Provenance, withImport: Boolean): String =
    val imports = if withImport then s"import $base.*\n\n" else ""
    s"""// Generated from the pinned OBS schema (sha256: ${provenance.sha256}). Do not edit.
       |package $pkg
       |
       |$imports""".stripMargin
