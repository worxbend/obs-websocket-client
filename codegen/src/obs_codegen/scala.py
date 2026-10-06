"""Scala lexical escaping, deliberately separate from HTML/JSON escaping."""

import re

BASE_PACKAGE = "com.worxbend.obs.websocket.client.protocol"


def quote(value: str) -> str:
    parts = []
    for char in value:
        if char == '"':
            parts.append('\\"')
        elif char == "\\":
            parts.append("\\\\")
        elif ord(char) < 32:
            parts.append(f"\\u{ord(char):04x}")
        else:
            parts.append(char)
    return '"' + "".join(parts) + '"'


def collapse(value: str) -> str:
    # java.lang.String.trim and Java's default ASCII \s, not Python's Unicode strip.
    trimmed = value.strip("".join(chr(i) for i in range(33)))
    return re.sub(r"[ \t\n\x0b\f\r]+", " ", trimmed).replace("*/", "* /")
