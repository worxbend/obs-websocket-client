package com.worxbend.obs.websocket.client

import java.util.UUID

/** Explicit source of nondeterminism. Tests can supply a reproducible ID sequence. */
final case class SessionDependencies(nextRequestId: () => String)
object SessionDependencies:
  val live: SessionDependencies = SessionDependencies(() => UUID.randomUUID().toString)
