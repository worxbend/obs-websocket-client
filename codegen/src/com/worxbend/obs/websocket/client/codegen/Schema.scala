package com.worxbend.obs.websocket.client.codegen

private[codegen] final case class Schema(
    requests: List[SchemaRequest],
    events: List[SchemaEvent],
    enums: List[SchemaEnum] = Nil
)
