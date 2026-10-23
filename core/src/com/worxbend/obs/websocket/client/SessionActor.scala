package com.worxbend.obs.websocket.client

import ox.channels.{ActorRef, ChannelClosedException}
import ox.discard
import ox.either.catching
import scala.util.control.NonFatal

/** Shared ask/tell idioms for the session actor. Closed mailbox = session gone. */
private[client] object SessionActor:
  extension (logic: ActorRef[SessionLogic])
    /** Ask; closed mailbox maps to [[ObsError.Closed]]. */
    def askOrClosed[A](f: SessionLogic => Either[ObsError, A]): Either[ObsError, A] =
      logic.ask(f).catching[ChannelClosedException].left.map(_ => ObsError.Closed).flatten

    /** Best-effort bookkeeping tell: a defect in `f` or a closed mailbox is swallowed, so cleanup accounting never
      * kills the actor or escapes the session scope raw.
      */
    def tellSafely(f: SessionLogic => Unit): Unit =
      logic
        .tell(state =>
          try f(state)
          catch case NonFatal(_) => ()
        )
        .catching[ChannelClosedException]
        .discard
