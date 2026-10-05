package com.worxbend.obs.websocket.client.codegen.model

/** Immutable rendering input. Lists are ordered before rendering, except inventory rows which sort as text. */
private[codegen] final case class NormalizedSchema(
    requests: List[RequestDefinition],
    events: List[EventDefinition],
    enums: List[EnumDefinition],
    categories: List[RequestCategory],
    inventory: List[InventoryEntry]
)
