package com.worxbend.obs.websocket.client.codegen.model

/** A validated event payload and its upstream documentation. */
final private[codegen] case class EventDefinition(name: String, fields: List[Field], documentation: Documentation)
