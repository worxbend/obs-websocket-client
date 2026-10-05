package com.worxbend.obs.websocket.client.server

import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec
import _root_.sttp.tapir.Schema

/** Stable public error; upstream comments and credentials are never exposed. */
final private[server] case class ApiFailure(message: String) derives ConfiguredJsonValueCodec, Schema
