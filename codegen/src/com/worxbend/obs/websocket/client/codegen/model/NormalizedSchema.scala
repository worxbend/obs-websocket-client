package com.worxbend.obs.websocket.client.codegen.model

/** Immutable handoff from schema normalization to output orchestration.
  *
  * Request/event/enum lists are sorted by upstream name; their fields retain declaration order. Categories contain
  * references to the same normalized request values, so facades and payload codecs cannot independently interpret a
  * schema field. Inventory rows sort as rendered text in their template. Renderers consume these decisions rather than
  * consulting raw JSON or overrides. Construct this model through SchemaNormalizer for production generation.
  */
final private[codegen] case class NormalizedSchema(
  requests:   List[RequestDefinition],
  events:     List[EventDefinition],
  enums:      List[EnumDefinition],
  categories: List[RequestCategory],
  inventory:  List[InventoryEntry],
)
