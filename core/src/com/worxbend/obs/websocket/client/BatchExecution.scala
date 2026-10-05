package com.worxbend.obs.websocket.client

import com.worxbend.obs.websocket.client.protocol.JsonObject

enum BatchExecution(val wireValue: Int):
  case SerialRealtime extends BatchExecution(wireValue = 0)
  case SerialFrame    extends BatchExecution(wireValue = 1)

  /** Retained as a wire value; nonempty parallel batches are rejected because OBS cannot reliably correlate results.
    * Not constructible outside the client: the runtime rejection in `batch` is a defensive fallback.
    */
  private[client] case Parallel extends BatchExecution(wireValue = 2)

enum BatchFailurePolicy:
  case Continue, Halt

/** Results retain their submitted position; a halted batch marks unexecuted entries explicitly. */
enum BatchResult:
  case Completed(requestType: String, result: Either[ObsError, JsonObject])
  case NotExecuted(requestType: String)
