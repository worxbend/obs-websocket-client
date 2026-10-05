package com.worxbend.obs.websocket.client

import com.worxbend.obs.websocket.client.protocol.*

private[client] object SessionWire:
  /** obs-websocket 5.x opcodes the session sends and matches on. */
  object Op:
    val Hello                = 0
    val Identify             = 1
    val Identified           = 2
    val Reidentify           = 3
    val Event                = 5
    val Request              = 6
    val RequestResponse      = 7
    val RequestBatch         = 8
    val RequestBatchResponse = 9

  /** A byte-limit rejection means the frame never reached the parser; keep it distinct from malformed input. */
  def malformed(error: ProtocolError): ObsError = error match
    case ProtocolError.SizeLimit => ObsError.MessageTooLarge(message = "Incoming message exceeds configured byte limit")
    case other                   => ObsError.MalformedPayload(path = other.path, message = other.message)

  /** Local pre-send catalog validation is not a wire failure. */
  def invalid(error: ProtocolError): ObsError = ObsError.InvalidRequest(path = error.path, message = error.message)

  def responseData(data: JsonObject, expectedType: String, id: String): Either[ObsError, JsonObject] =
    for
      actualType <- data.string(name = "requestType").left.map(malformed)
      _          <-
        if actualType == expectedType then Right(())
        else Left(ObsError.MalformedPayload(path = "requestType", message = "Response type does not match request"))
      status  <- data.obj(name = "requestStatus").left.map(malformed)
      success <- status.boolean(name = "result").left.map(malformed)
      code    <- status.int(name = "code").left.map(malformed)
      comment <- status.optionalString(name = "comment").left.map(malformed)
      result  <-
        if !success then
          Left(ObsError.RequestRejected(requestType = actualType, requestId = id, code = code, comment = comment))
        else
          data.fields.get("responseData") match
            case None                    => Right(JsonObject.empty)
            case Some(value: JsonObject) => Right(value)
            case _ => Left(ObsError.MalformedPayload(path = "responseData", message = "Expected object"))
    yield result
