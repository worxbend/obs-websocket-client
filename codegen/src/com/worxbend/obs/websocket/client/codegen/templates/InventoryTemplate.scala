package com.worxbend.obs.websocket.client.codegen.templates

import com.worxbend.obs.websocket.client.codegen.model.InventoryEntry
import SourceFragments.collapse

/** Tab-separated inventory layout; generated bindings are not evidence of live OBS verification.
  *
  * Produces `catalog-inventory.tsv`, a review artifact beside the generated Scala sources. Each row identifies a
  * request or event, initial version, generation status, and documented restrictions. Rows are sorted after rendering
  * for deterministic output; this file does not participate in Scala compilation.
  */
private[codegen] object InventoryTemplate:
  /** Raw metadata is independent of output escaping and row ordering. */
  final case class Context(entries: List[InventoryEntry])

  /** Renders lexically sorted rows and the historical final newline, including an empty inventory. */
  def render(context: Context): String =
    val rows = context.entries.map(renderRow).sorted.mkString("\n")
    s"""kind\tname\tinitial-version\tstatus\trestrictions
       |$rows
       |""".stripMargin

  private def renderRow(entry: InventoryEntry): String =
    s"${entry.kind}\t${entry.name}\t${entry.initialVersion}\tgenerated; live OBS verification deferred\t${collapse(entry.restrictions)}"
