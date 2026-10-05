package com.worxbend.obs.websocket.client.codegen

/** Lexical escaping shared by normalized enum expressions and source templates. */
private[codegen] object ScalaLiteral:
  /** Escapes a schema string for embedding in a generated Scala string literal. */
  def quote(value: String): String = value
    .flatMap:
      case '"'          => "\\\""
      case '\\'         => "\\\\"
      case c if c < ' ' => f"\\u${c.toInt}%04x"
      case c            => c.toString
    .mkString("\"", "", "\"")
