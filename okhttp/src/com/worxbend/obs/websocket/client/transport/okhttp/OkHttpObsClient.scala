package com.worxbend.obs.websocket.client.transport.okhttp

import com.worxbend.obs.websocket.client.transport.sttp.{SttpObsClient, SttpOptions}
import com.worxbend.obs.websocket.client.{ObsConfig, ObsError, ObsSession}
import java.util.concurrent.locks.LockSupport
import okhttp3.OkHttpClient
import ox.{resourceScope, useInScope}
import scala.concurrent.duration.*
import sttp.client4.okhttp.OkHttpSyncBackend

/** OkHttp entrypoints own each connection for precisely the callback's lifetime, mirroring `SttpObsClient`. Callers
  * with their own client or backend belong at `SttpObsClient.withBackend` directly, so this module deliberately adds
  * no injected-backend variant.
  *
  * One observable difference from the JDK backend: an HTTP upgrade rejection surfaces as "WebSocket connection failed"
  * rather than "WebSocket upgrade rejected", because OkHttp reports it as a generic `ProtocolException` instead of the
  * JDK's dedicated handshake exception.
  *
  * Size enforcement differs likewise: OkHttp aggregates continuation frames internally before delivery, and
  * `OkHttpClient.Builder` (verified against OkHttp 5.5.0) exposes no message or frame size cap, so `maxMessageBytes`
  * is checked on the already-materialized message and a breach aborts the connection — fail-fast, but after one
  * oversized allocation.
  */
object OkHttpObsClient:
  /** OkHttp's `close()` only queues the graceful Close frame on a writer thread, so an immediate force-close would
    * race the frame off the wire. Abortion therefore gives an in-progress graceful close a bounded grace to complete,
    * then cancels every in-flight call on the owned dispatcher, which promptly and idempotently force-closes this
    * connection and unblocks pending reads and writes; the dispatcher itself stays usable, so repeated invocation is
    * harmless. Scope exit shuts down the dispatcher's executor and evicts the connection pool.
    */
  def connect[A](
    config:        ObsConfig,
    options:       SttpOptions = SttpOptions(),
    clientOptions: OkHttpClientOptions = OkHttpClientOptions(),
  )(use: ObsSession => A): Either[ObsError, A] =
    config.validate.flatMap: valid =>
      options.validate.flatMap: validOptions =>
        clientOptions.validate.flatMap: validClientOptions =>
          resourceScope:
            val client =
              useInScope(validClientOptions.build(connectionTimeout = valid.connectionTimeout))(release)
            SttpObsClient.withBackend(
              backend = OkHttpSyncBackend.usingClient(client = client),
              config  = valid,
              () => abort(client = client),
              options = validOptions,
            )(
              use = use
            )

  /** `parkNanos` also returns early on interruption, so a cancelled caller degrades to a bounded busy poll that still
    * ends in the same force-close, never in a swallowed interrupt.
    */
  private def abort(client: OkHttpClient): Unit =
    val deadline = AbortGrace.fromNow
    while client.dispatcher.runningCallsCount() > 0 && deadline.hasTimeLeft() do
      LockSupport.parkNanos(AbortPoll.toNanos)
    client.dispatcher.cancelAll()

  private def release(client: OkHttpClient): Unit =
    client.dispatcher.executorService.shutdown()
    client.connectionPool.evictAll()

  private val AbortGrace: FiniteDuration = 100.millis
  private val AbortPoll: FiniteDuration  = 2.millis
