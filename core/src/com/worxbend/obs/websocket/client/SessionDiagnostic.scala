package com.worxbend.obs.websocket.client

enum DiagnosticOutcome:
  case Succeeded, Failed, Cancelled
  case Rejected(code: Int)

object DiagnosticOutcome:
  private[client] def from(result: Either[ObsError, ?]): DiagnosticOutcome = result match
    case Right(_)                                      => Succeeded
    case Left(ObsError.RequestRejected(_, _, code, _)) => Rejected(code = code)
    case Left(_)                                       => Failed

enum TrafficDirection:
  case Sent, Received

/** Metadata only: no frames, response data, credentials, URI or server comments. */
enum SessionDiagnostic:
  case Traffic(direction: TrafficDirection, bytes: Int)
  case RequestFinished(requestType: String, requestId: String, elapsedNanos: Long, outcome: DiagnosticOutcome)
  case StateChanged(state: ConnectionState)

final case class SessionStats(
  sentMessages:        Long = 0L,
  sentBytes:           Long = 0L,
  receivedMessages:    Long = 0L,
  receivedBytes:       Long = 0L,
  completedRequests:   Long = 0L,
  failedRequests:      Long = 0L,
  receivedEvents:      Long = 0L,
  requestElapsedNanos: Long = 0L,
)
