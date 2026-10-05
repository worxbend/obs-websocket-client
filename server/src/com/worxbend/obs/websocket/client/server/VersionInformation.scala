package com.worxbend.obs.websocket.client.server

import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec
import _root_.sttp.tapir.Schema

final private[server] case class VersionInformation(obs: String, websocket: String)
    derives ConfiguredJsonValueCodec,
      Schema
