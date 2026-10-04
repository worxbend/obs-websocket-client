package com.worxbend.obs.websocket.client

import com.worxbend.obs.websocket.client.protocol.*

private[client] object SessionWire:
  /** obs-websocket 5.x opcodes the session sends and matches on. */
  object Op:
    val Hello = 0
    val Identify = 1
    val Identified = 2
    val Reidentify = 3
    val Event = 5
    val Request = 6
    val RequestResponse = 7
    val RequestBatch = 8
    val RequestBatchResponse = 9

  /** A byte-limit rejection means the frame never reached the parser; keep it distinct from malformed input. */
  def malformed(error: ProtocolError): ObsError = error match
    case ProtocolError.SizeLimit => ObsError.MessageTooLarge("Incoming message exceeds configured byte limit")
    case other                   => ObsError.MalformedPayload(other.path, other.message)

  /** Local pre-send catalog validation is not a wire failure. */
  def invalid(error: ProtocolError): ObsError = ObsError.InvalidRequest(error.path, error.message)

  def responseData(data: JsonObject, expectedType: String, id: String): Either[ObsError, JsonObject] =
    for
      actualType <- data.string("requestType").left.map(malformed)
      _ <-
        if actualType == expectedType then Right(())
        else Left(ObsError.MalformedPayload("requestType", "Response type does not match request"))
      status <- data.obj("requestStatus").left.map(malformed)
      success <- status.boolean("result").left.map(malformed)
      code <- status.int("code").left.map(malformed)
      comment <- status.optionalString("comment").left.map(malformed)
      result <-
        if !success then Left(ObsError.RequestRejected(actualType, id, code, comment))
        else
          data.fields.get("responseData") match
            case None                    => Right(JsonObject.empty)
            case Some(value: JsonObject) => Right(value)
            case _                       => Left(ObsError.MalformedPayload("responseData", "Expected object"))
    yield result
