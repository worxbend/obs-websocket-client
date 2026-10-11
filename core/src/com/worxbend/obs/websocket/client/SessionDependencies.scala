package com.worxbend.obs.websocket.client

import java.util.UUID

/** Explicit sources of nondeterminism. Callbacks must be prompt and thread-safe; `nanoTime` must be monotonic and
  * side-effect-free. IDs are seeds, not final wire IDs: a connection-local ordinal ensures lifetime uniqueness even
  * with repeated seeds, including across startup discovery and the public session.
  */
final case class SessionDependencies(nextRequestId: () => String, nanoTime: () => Long = () => System.nanoTime())
object SessionDependencies:
  val live: SessionDependencies = SessionDependencies(() => UUID.randomUUID().toString)
