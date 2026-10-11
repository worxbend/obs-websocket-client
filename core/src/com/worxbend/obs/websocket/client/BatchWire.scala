package com.worxbend.obs.websocket.client

import com.worxbend.obs.websocket.client.protocol.*

/** Pure validation of submitted positions, identities and halted-result sequences. */
private[client] object BatchWire:
  def decode(
    data:     JsonObject,
    requests: Vector[PreparedRequest],
    policy:   BatchFailurePolicy,
  ): Either[ObsError, Vector[BatchResult]] =
    data
      .array(name = "results")
      .left
      .map(SessionWire.malformed)
      .flatMap: results =>
        if results.size > requests.size || (policy == BatchFailurePolicy.Continue && results.size != requests.size) then
          Left(
            ObsError
              .MalformedPayload(path = "results", message = "Batch result count does not match submitted requests")
          )
        else
          val decoded = requests.zipWithIndex.foldLeft[Either[ObsError, Vector[BatchResult]]](Right(Vector.empty)):
            case (acc, (request, index)) =>
              acc.flatMap: values =>
                results.lift(index) match
                  case None => Right(values :+ BatchResult.NotExecuted(requestType = request.requestType))
                  case Some(result: JsonObject) =>
                    SessionWire.responseData(
                      data         = result,
                      expectedType = request.requestType,
                      id           = s"batch:$index",
                    ) match
                      case Left(error: ObsError.MalformedPayload) => Left(error)
                      case response                               =>
                        Right(values :+ BatchResult.Completed(requestType = request.requestType, result = response))
                  case _ => Left(ObsError.MalformedPayload(path = s"results[$index]", message = "Expected object"))
          decoded.flatMap: values =>
            val firstFailure = values.indexWhere:
              case BatchResult.Completed(_, Left(_)) => true
              case _                                 => false
            if policy == BatchFailurePolicy.Halt && !haltSequenceValid(
                firstFailure = firstFailure,
                resultCount  = results.size,
                requestCount = requests.size,
              )
            then
              Left(
                ObsError
                  .MalformedPayload(path = "results", message = "Halted batch must end immediately after a failure")
              )
            else Right(values)

  /** A halted batch is coherent only when every request executed without a failure, or the first failure is the last
    * returned result and nothing follows it.
    */
  private def haltSequenceValid(firstFailure: Int, resultCount: Int, requestCount: Int): Boolean =
    (firstFailure >= 0 && firstFailure == resultCount - 1) || (firstFailure < 0 && resultCount == requestCount)
