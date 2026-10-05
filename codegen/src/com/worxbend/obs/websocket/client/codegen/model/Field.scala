package com.worxbend.obs.websocket.client.codegen.model

/** Resolved Scala field semantics, shared by payload and request-facade rendering. `name` is the wire key; `identifier`
  * is the collision-safe Scala name. Placeholder expressions are selected alongside the Scala type so those two
  * mappings cannot drift.
  */
private[codegen] final case class Field(
    name: String,
    identifier: String,
    scalaType: String,
    codec: String,
    optional: Boolean,
    nullable: Boolean,
    description: String,
    placeholder: String
)
