package com.worxbend.obs.websocket.client.codegen.model

/** Resolved Scala field semantics, shared by payload and request-facade rendering. `name` is the wire key; `identifier`
  * is the collision-safe Scala name. Placeholder expressions are selected alongside the Scala type so those two
  * mappings cannot drift.
  *
  * @param name
  *   original JSON key, including dots for fields represented inside nested objects
  * @param identifier
  *   Scala member name before backtick quoting; may carry a `payload` prefix to avoid envelope collisions
  * @param scalaType
  *   base Scala payload type before optional/nullable wrapping, for example `BigDecimal`
  * @param codec
  *   Scala source expression for the base value codec, for example `ValueCodec.number`
  * @param optional
  *   whether omission is accepted; rendered as protocol `Field[T]` with default `Field.Missing`
  * @param nullable
  *   whether explicit JSON null is accepted; required nullable values use `Option[T]`, while optional values use the
  *   protocol Field representation and pass this flag to the decoder
  * @param description
  *   combined upstream description/restrictions/omission behavior, escaped by the documentation renderer
  * @param placeholder
  *   Scala expression for a required argument in generated minimal requests; not a validated OBS value
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
