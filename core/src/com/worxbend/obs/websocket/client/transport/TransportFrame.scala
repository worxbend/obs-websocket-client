package com.worxbend.obs.websocket.client.transport

/** Backend-neutral frame model for the shared transport loop in [[AbstractObsTransport]]. Adapters map their backend's
  * frame type onto this ADT at the `socketBoundary` layer. `Binary` and `Pong` carry no payload because the loop only
  * ever rejects the former and skips the latter; `Close` carries only the status code because peer-controlled reason
  * text is never reflected into errors.
  */
private[client] enum TransportFrame:
  case Text(payload: String, finalFragment: Boolean)
  case Binary
  case Ping(payload: Array[Byte])
  case Pong
  case Close(statusCode: Int)
