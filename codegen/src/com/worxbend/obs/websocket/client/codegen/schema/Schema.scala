package com.worxbend.obs.websocket.client.codegen.schema

/** Raw pinned protocol catalog, before validation or Scala-specific normalization. */
private[codegen] final case class Schema(
    requests: List[SchemaRequest],
    events: List[SchemaEvent],
    enums: List[SchemaEnum] = Nil
)
