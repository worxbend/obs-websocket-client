package com.worxbend.obs.websocket.client.bench

/** Deterministic synthetic payloads shared by the benchmarks. No randomness, so every fork measures identical input. */
private[bench] object Payloads:
  /** ~50 chars: a minimal OBS handshake-shaped message. */
  val shortAscii: String = """{"op":0,"d":{"rpcVersion":1,"eventSubscriptions":33}}"""

  /** ~1-2 KB: an OBS-shaped JSON message with nested event data. */
  val obsMessage: String =
    val sceneItem = (id: Int) =>
      s"""{"sceneItemId":$id,"sceneItemEnabled":true,"sceneItemIndex":${id % 8},"sourceName":"capture-$id"}"""
    val items = (1 to 12).map(sceneItem).mkString(",")
    s"""{"op":5,"d":{"eventType":"SceneItemEnableStateChanged","eventIntent":4,"eventData":{"sceneName":"main","sceneItems":[$items],"batchId":"a1b2c3d4e5f6","obsWebSocketVersion":"5.6.3","platform":"linux"}}}"""

  /** ~64 KiB of ASCII, standing in for a large batched event. */
  val largeAscii: String =
    val chunk   = s"""{"key":"value","nested":{"list":[1,2,3,4,5],"flag":false},"padding":"${"x" * 96}"},"""
    val builder = new StringBuilder(64 * 1024 + 16)
    builder.append('[')
    while builder.length < 64 * 1024 do builder.append(chunk)
    builder.append("null]").toString

  /** Multi-byte-heavy text: CJK (3-byte UTF-8) and emoji (4-byte surrogate pairs). */
  val cjkEmoji: String =
    val unit    = "配信シーン切替🎥録画開始🔴チャット通知💬"
    val builder = new StringBuilder(unit.length * 64 + 32)
    builder.append("{\"events\":\"")
    var index = 0
    while index < 64 do
      builder.append(unit)
      index += 1
    builder.append("\"}").toString
