package com.worxbend.obs.websocket.client.codegen.model

/** Stable facade names and their already-normalized, alphabetically ordered requests. */
final private[codegen] case class RequestCategory(name: String, className: String, requests: List[RequestDefinition])
