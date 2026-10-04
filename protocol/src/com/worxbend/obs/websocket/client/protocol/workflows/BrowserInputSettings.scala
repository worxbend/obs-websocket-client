package com.worxbend.obs.websocket.client.protocol.workflows

import com.worxbend.obs.websocket.client.protocol.*
import com.worxbend.obs.websocket.client.protocol.requests.SetInputSettings

/** An immutable browser-source patch. Unknown existing settings survive mergeInto and OBS overlay mode. */
final class BrowserInputSettings private (val toJson: JsonObject):
  def withSize(width: Int, height: Int): Either[ProtocolError, BrowserInputSettings] =
    if width <= 0 || height <= 0 then Left(ProtocolError("browser.size", "Dimensions must be positive"))
    else Right(updated(Map("width" -> JsonValue.Num(BigDecimal(width)), "height" -> JsonValue.Num(BigDecimal(height)))))

  def withFrameRate(fps: Int): Either[ProtocolError, BrowserInputSettings] =
    if fps <= 0 then Left(ProtocolError("browser.fps", "Frame rate must be positive"))
    else Right(updated(Map("fps" -> JsonValue.Num(BigDecimal(fps)), "fps_custom" -> JsonValue.Bool(true))))

  def withCss(css: String): BrowserInputSettings = updated(Map("css" -> JsonValue.Str(css)))
  def shutdownWhenHidden: BrowserInputSettings = updated(Map("shutdown" -> JsonValue.Bool(true)))
  def keepRunningWhenHidden: BrowserInputSettings = updated(Map("shutdown" -> JsonValue.Bool(false)))
  def restartWhenActive: BrowserInputSettings = updated(Map("restart_when_active" -> JsonValue.Bool(true)))
  def keepPageWhenActive: BrowserInputSettings = updated(Map("restart_when_active" -> JsonValue.Bool(false)))
  def mergeInto(existing: JsonObject): JsonObject = JsonObject(existing.fields ++ toJson.fields)
  def set(input: InputRef): SetInputSettings =
    SetInputSettings(
      inputName = input.name,
      inputUuid = input.uuid,
      inputSettings = toJson,
      overlay = Field.Value(true)
    )

  private def updated(fields: Map[String, JsonValue]): BrowserInputSettings =
    new BrowserInputSettings(JsonObject(toJson.fields ++ fields))

object BrowserInputSettings:
  val empty: BrowserInputSettings = new BrowserInputSettings(JsonObject.empty)

  def remote(url: String): Either[ProtocolError, BrowserInputSettings] = location("url", url, false)
  def local(path: String): Either[ProtocolError, BrowserInputSettings] = location("local_file", path, true)

  private def location(key: String, value: String, local: Boolean): Either[ProtocolError, BrowserInputSettings] =
    if value.trim.isEmpty then Left(ProtocolError(s"browser.$key", "Location must not be blank"))
    else
      Right(
        new BrowserInputSettings(JsonObject(Map(key -> JsonValue.Str(value), "is_local_file" -> JsonValue.Bool(local))))
      )
