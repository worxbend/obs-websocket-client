package com.worxbend.obs.websocket.client.codegen.model

/** A validated request normalized once for both its payload file and category facade. */
final private[codegen] case class RequestDefinition(
  name:           String,
  requestFields:  List[Field],
  responseFields: List[Field],
  documentation:  Documentation,
  category:       String,
  methodName:     String,
)
