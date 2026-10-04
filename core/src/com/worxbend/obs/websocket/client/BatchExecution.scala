package com.worxbend.obs.websocket.client

import com.worxbend.obs.websocket.client.protocol.JsonObject

enum BatchExecution(val wireValue: Int):
  case SerialRealtime extends BatchExecution(0)
  case SerialFrame extends BatchExecution(1)
  case Parallel extends BatchExecution(2)

enum BatchFailurePolicy:
  case Continue, Halt

/** Results retain their submitted position; a halted batch marks unexecuted entries explicitly. */
enum BatchResult:
  case Completed(requestType: String, result: Either[ObsError, JsonObject])
  case NotExecuted(requestType: String)
