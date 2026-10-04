package com.worxbend.obs.websocket.client

import com.worxbend.obs.websocket.client.protocol.*

private[client] object SessionWire:
  def malformed(error: ProtocolError): ObsError = ObsError.MalformedPayload(error.path, error.message)

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
