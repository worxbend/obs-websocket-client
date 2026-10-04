package com.worxbend.obs.websocket.client.server

import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec
import _root_.sttp.tapir.Schema

/** Liveness only: this endpoint makes no claim that OBS is connected. */
private[server] final case class Health(status: String) derives ConfiguredJsonValueCodec, Schema
