package com.worxbend.obs.websocket.client.codegen.model

/** Inventory metadata before tab-separated rendering; restrictions retain upstream order. */
final private[codegen] case class InventoryEntry(
  kind:           String,
  name:           String,
  initialVersion: String,
  restrictions:   String,
)
