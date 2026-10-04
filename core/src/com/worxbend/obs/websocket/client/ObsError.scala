package com.worxbend.obs.websocket.client

/** Expected failures. Messages never include complete wire frames or credentials.
  *
  * Retry classification for the opt-in reconnect entrypoint: `Transport` without a close code, `Transport` with a
  * transient close code (1001, 1006, 1011, 1012, 1013), and `Timeout` may retry. `MessageTooLarge`,
  * `UnsupportedMessage`, `InvalidConfiguration`, `InvalidRequest`, and `InternalError` are deterministic and never
  * retry; neither do authentication, protocol, malformed-payload, rejection, overflow, or closed-session failures.
  */
enum ObsError:
  case InvalidConfiguration(message: String)
  case Transport(message: String, closeCode: Option[Int] = None)
  case Authentication(message: String, closeCode: Option[Int] = None)
  case IncompatibleProtocol(version: Int)
  case MalformedPayload(path: String, message: String)
  case UnexpectedMessage(state: ConnectionState, opcode: Int)

  /** A request failed local catalog validation before anything was sent. Unlike `MalformedPayload` — a wire-level
    * failure reported by the peer or the frame decoder — this is a deterministic local rejection.
    */
  case InvalidRequest(path: String, message: String)

  /** A message exceeded the configured byte limit. Nothing was written to or read from the socket beyond the limit, so
    * the condition recurs identically after reconnect. An outbound occurrence completes only the offending request or
    * batch; the session stays alive. An inbound occurrence fails the session.
    */
  case MessageTooLarge(message: String)

  /** An incoming WebSocket frame the client can never use, such as a binary frame in JSON mode. Session-fatal and never
    * retryable.
    */
  case UnsupportedMessage(message: String)

  /** A violated internal library invariant: a bug or a broken injected dependency, never a user configuration problem.
    */
  case InternalError(message: String)

  case RequestRejected(requestType: String, requestId: String, code: Int, comment: Option[String])
  case Timeout(operation: String)
  case Overflow(resource: String)
  case UnsupportedRequest(requestType: String)
  case AmbiguousBatchOutcome(cause: ObsError)
  case Closed
