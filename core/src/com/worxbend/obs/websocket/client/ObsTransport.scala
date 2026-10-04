package com.worxbend.obs.websocket.client

/** Internal transport boundary, public for custom transports and deterministic peers. receive/send must be
  * interruptible; close must be bounded and safe to call twice. A transport must aggregate text fragments and enforce
  * its frame byte limit.
  *
  * Error contract: `send` reports a deterministic local rejection, such as an encoded frame over the byte limit, as
  * `Left(ObsError.MessageTooLarge)`; the session then fails only that request or batch and keeps running. Any other
  * `Left` from `send` or `receive` fails the session. An unchecked exception thrown by `send` or `receive` is a
  * transport defect: the session fails with a non-retryable `ObsError.InternalError` carrying the cause instead of
  * stalling silently. Interruption is never swallowed. Nonfatal exceptions from `close` are suppressed to preserve the
  * session result; custom transports must still release their resources before throwing.
  */
trait ObsTransport:
  def receive(): Either[ObsError, String]
  def send(text: String): Either[ObsError, Unit]
  def close(): Unit
