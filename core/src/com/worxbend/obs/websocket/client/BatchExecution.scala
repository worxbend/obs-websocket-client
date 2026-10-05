package com.worxbend.obs.websocket.client

import com.worxbend.obs.websocket.client.protocol.JsonObject

/** Wire-level execution ordering sent with a batch request. */
enum BatchExecution(val wireValue: Int):
  /** Entries execute in order, in realtime: each runs as soon as the previous one completes. */
  case SerialRealtime extends BatchExecution(wireValue = 0)

  /** Entries execute in order on frame ticks: one entry per rendered frame. */
  case SerialFrame    extends BatchExecution(wireValue = 1)

  /** Retained as a wire value; nonempty parallel batches are rejected because OBS cannot reliably correlate results.
    * Not constructible outside the client: the runtime rejection in `batch` is a defensive fallback.
    */
  private[client] case Parallel extends BatchExecution(wireValue = 2)

/** Whether a failed batch entry aborts the remaining entries. */
enum BatchFailurePolicy:
  /** Every entry executes; failures are reported per entry. */
  case Continue

  /** Halt on the first failure; entries after it come back as not executed. */
  case Halt

/** Results retain their submitted position; a halted batch marks unexecuted entries explicitly. */
enum BatchResult:
  case Completed(requestType: String, result: Either[ObsError, JsonObject])
  case NotExecuted(requestType: String)
