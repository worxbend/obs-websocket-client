package com.worxbend.obs.websocket.client.codegen.schema

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import java.nio.file.Path

/** Decodes supplied bytes; file ownership and checksum verification belong to the runner. */
private[codegen] object SchemaParser:
  private given JsonValueCodec[SchemaField] = JsonCodecMaker.make
  private given JsonValueCodec[SchemaRequest] = JsonCodecMaker.make
  private given JsonValueCodec[SchemaEvent] = JsonCodecMaker.make
  private given JsonValueCodec[SchemaEnumEntry] = JsonCodecMaker.make
  private given JsonValueCodec[SchemaEnum] = JsonCodecMaker.make
  private given JsonValueCodec[Schema] = JsonCodecMaker.make
  private given JsonValueCodec[Overrides] = JsonCodecMaker.make
  private given JsonValueCodec[Provenance] = JsonCodecMaker.make

  /** Decodes the catalog while retaining the source path in failures. */
  def schema(bytes: Array[Byte], path: Path): Schema = parseJson[Schema](bytes, path, "protocol schema")

  /** Decodes reviewed semantic overrides without performing normalization. */
  def overrides(bytes: Array[Byte], path: Path): Overrides = parseJson[Overrides](bytes, path, "generation overrides")

  /** Decodes the pinned origin and expected checksum. */
  def provenance(bytes: Array[Byte], path: Path): Provenance = parseJson[Provenance](bytes, path, "schema provenance")

  private def parseJson[A](bytes: Array[Byte], path: Path, description: String)(using
      JsonValueCodec[A]
  ): A =
    try readFromArray[A](bytes)
    catch
      case error: Exception =>
        throw new IllegalArgumentException(s"Failed to parse $description from $path: ${error.getMessage}", error)
