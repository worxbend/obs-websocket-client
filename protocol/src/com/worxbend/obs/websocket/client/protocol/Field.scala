package com.worxbend.obs.websocket.client.protocol

/** Distinguishes omission, explicit JSON null and a supplied value. */
enum Field[+A]:
  case Missing
  case Null
  case Value(value: A)

object Field:
  /** A plain value means a supplied field: `sceneName = "Studio"` reads as `Field.Value("Studio")`. */
  given [A]: Conversion[A, Field[A]] = Value(_)
