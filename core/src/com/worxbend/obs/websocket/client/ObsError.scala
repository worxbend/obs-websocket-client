package com.worxbend.obs.websocket.client

/** Expected failures. Messages never include complete wire frames or credentials.
  *
  * Retry classification for the opt-in reconnect entrypoint: `Transport` without a close code, `Transport` with a
  * transient close code (1001, 1006, 1011, 1012, 1013), and `Timeout` may retry. `MessageTooLarge`,
  * `UnsupportedMessage`, `InvalidConfiguration`, `InvalidRequest`, and `InternalError` are deterministic and never
  * retry; neither do authentication, protocol, malformed-payload, rejection, overflow, or closed-session failures.
  */
enum ObsError:
  /** A configuration or option value failed validation before any connection attempt. Deterministic; never retry. */
  case InvalidConfiguration(message: String)

  /** Connection or socket-level failure. `closeCode` carries the WebSocket close code when the peer supplied one. */
  case Transport(message: String, closeCode: Option[Int] = None)

  /** OBS rejected the authentication handshake (bad or missing credentials); the session never becomes ready. */
  case Authentication(message: String, closeCode: Option[Int] = None)

  /** The server speaks an obs-websocket RPC version this client does not support. */
  case IncompatibleProtocol(version: Int)

  /** A wire frame failed local decoding or schema validation; always produced by the client decoder, never reported
    * by the peer.
    */
  case MalformedPayload(path: String, message: String)

  /** A protocol message arrived in a connection state where it is not allowed. Session-fatal. */
  case UnexpectedMessage(state: ConnectionState, opcode: Int)

  /** A request failed local catalog validation before anything was sent. Unlike `RequestRejected` — a failure
    * reported by the peer — this is a deterministic local rejection.
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

  /** The server rejected a request; `code` and `comment` come from the peer. Code 207 (NotReady) is special: it
    * signals the server is still starting and is the only rejection the readiness retry (`requestWhenReady`,
    * `ReadinessPolicy`) will retry.
    */
  case RequestRejected(requestType: String, requestId: String, code: Int, comment: Option[String])

  /** A named operation exceeded its configured deadline. For a request that reached the wire the outcome is
    * ambiguous: the server may already have executed a mutation.
    */
  case Timeout(operation: String)

  /** A bounded internal queue filled; `resource` names which one. An event-subscription overflow fails only that
    * subscription, never the session.
    */
  case Overflow(resource: String)

  /** The request type is absent from the server's advertised capability set (`ConnectionMetadata.availableRequests`);
    * rejected locally before anything was sent.
    */
  case UnsupportedRequest(requestType: String)

  /** The batch reached the server but its outcome is unknown: a mutation may already have executed. Wraps the
    * underlying transport, timeout or close failure.
    */
  case AmbiguousBatchOutcome(cause: ObsError)

  /** The session closed before the operation completed, or the operation was attempted after close. */
  case Closed
