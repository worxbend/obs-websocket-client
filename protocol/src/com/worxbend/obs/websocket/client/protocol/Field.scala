package com.worxbend.obs.websocket.client.protocol

/** Distinguishes omission, explicit JSON null and a supplied value. */
enum Field[+A]:
  case Missing
  case Null
  case Value(value: A)
