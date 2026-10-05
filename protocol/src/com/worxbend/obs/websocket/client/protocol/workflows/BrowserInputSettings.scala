package com.worxbend.obs.websocket.client.protocol.workflows

import com.worxbend.obs.websocket.client.protocol.*
import com.worxbend.obs.websocket.client.protocol.requests.SetInputSettings

/** An immutable browser-source patch. Unknown existing settings survive mergeInto and OBS overlay mode. */
final class BrowserInputSettings private (val toJson: JsonObject):
  def withSize(width: Int, height: Int): Either[ProtocolError, BrowserInputSettings] =
    if width <= 0 || height <= 0 then
      Left(ProtocolError(path = "browser.size", message = "Dimensions must be positive"))
    else
      Right(
        updated(fields =
          Map(
            "width"  -> JsonValue.Num(value = BigDecimal(width)),
            "height" -> JsonValue.Num(value = BigDecimal(height)),
          )
        )
      )

  def withFrameRate(fps: Int): Either[ProtocolError, BrowserInputSettings] =
    if fps <= 0 then Left(ProtocolError(path = "browser.fps", message = "Frame rate must be positive"))
    else
      Right(
        updated(fields =
          Map("fps" -> JsonValue.Num(value = BigDecimal(fps)), "fps_custom" -> JsonValue.Bool(value = true))
        )
      )

  def withCss(css: String): BrowserInputSettings  = updated(fields = Map("css" -> JsonValue.Str(value = css)))
  def shutdownWhenHidden: BrowserInputSettings    = updated(fields = Map("shutdown" -> JsonValue.Bool(value = true)))
  def keepRunningWhenHidden: BrowserInputSettings = updated(fields = Map("shutdown" -> JsonValue.Bool(value = false)))
  def restartWhenActive: BrowserInputSettings     =
    updated(fields = Map("restart_when_active" -> JsonValue.Bool(value = true)))
  def keepPageWhenActive: BrowserInputSettings =
    updated(fields = Map("restart_when_active" -> JsonValue.Bool(value = false)))
  def mergeInto(existing: JsonObject): JsonObject = JsonObject(fields = existing.fields ++ toJson.fields)
  def set(input: InputRef): SetInputSettings      =
    SetInputSettings(
      inputName     = input.name,
      inputUuid     = input.uuid,
      inputSettings = toJson,
      overlay       = Field.Value(value = true),
    )

  private def updated(fields: Map[String, JsonValue]): BrowserInputSettings =
    new BrowserInputSettings(toJson = JsonObject(fields = toJson.fields ++ fields))

object BrowserInputSettings:
  val empty: BrowserInputSettings = new BrowserInputSettings(toJson = JsonObject.empty)

  def remote(url: String): Either[ProtocolError, BrowserInputSettings] =
    location(key = "url", value = url, local = false)
  def local(path: String): Either[ProtocolError, BrowserInputSettings] =
    location(key = "local_file", value = path, local = true)

  private def location(key: String, value: String, local: Boolean): Either[ProtocolError, BrowserInputSettings] =
    if value.trim.isEmpty then Left(ProtocolError(path = s"browser.$key", message = "Location must not be blank"))
    else
      Right(
        new BrowserInputSettings(toJson =
          JsonObject(fields =
            Map(key -> JsonValue.Str(value = value), "is_local_file" -> JsonValue.Bool(value = local))
          )
        )
      )
