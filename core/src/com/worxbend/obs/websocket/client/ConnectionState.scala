package com.worxbend.obs.websocket.client

/** Session phases reported by `session.state`.
  *
  * The session handle is handed to application code only after identification, so `session.state` observably returns
  * only `Ready`, `Closed`, or `Failed`; `AwaitingHello` and `Identifying` complete before the handle exists.
  * `Connecting` and `Closing` are reserved structural states: the transition table admits them so the machine stays
  * total, but the live session never enters them. Matching on either is a dead arm.
  */
enum ConnectionState:
  case Connecting, AwaitingHello, Identifying, Ready, Closing, Closed, Failed

object ConnectionState:
  /** Pure transition table, also used by the live session. */
  def transition(from: ConnectionState, to: ConnectionState): Either[ObsError, ConnectionState] =
    val allowed = (from, to) match
      case (Connecting, AwaitingHello) | (AwaitingHello, Identifying) | (Identifying, Ready) => true
      case (Connecting | AwaitingHello | Identifying | Ready, Closing | Failed)              => true
      case (Closing, Closed)                                                                 => true
      case _                                                                                 => false
    if allowed then Right(to) else Left(ObsError.InternalError(message = s"Invalid state transition: $from -> $to"))
