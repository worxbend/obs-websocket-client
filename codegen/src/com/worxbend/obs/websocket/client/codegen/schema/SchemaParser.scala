package com.worxbend.obs.websocket.client.codegen.schema

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import java.nio.file.Path

/** JSON decoding boundary between input bytes and upstream-shaped records.
  *
  * The runner reads files and checks their checksum; this object only decodes the supplied bytes. The `path` arguments
  * are diagnostic labels, not files opened by these methods. Derived codecs retain the fields modeled in this package
  * and skip extra upstream metadata. Missing optional metadata uses the case-class defaults.
  *
  * Decoding establishes JSON shape, not generation semantics. `SchemaNormalizer` subsequently resolves supported types,
  * nullable overrides, naming collisions, and enum expressions before any template is called. Parse failures are
  * wrapped in IllegalArgumentException with the input description/path and the original exception as cause.
  */
private[codegen] object SchemaParser:
  private given JsonValueCodec[SchemaField]     = JsonCodecMaker.make
  private given JsonValueCodec[SchemaRequest]   = JsonCodecMaker.make
  private given JsonValueCodec[SchemaEvent]     = JsonCodecMaker.make
  private given JsonValueCodec[SchemaEnumEntry] = JsonCodecMaker.make
  private given JsonValueCodec[SchemaEnum]      = JsonCodecMaker.make
  private given JsonValueCodec[Schema]          = JsonCodecMaker.make
  private given JsonValueCodec[Overrides]       = JsonCodecMaker.make
  private given JsonValueCodec[Provenance]      = JsonCodecMaker.make

  /** Decodes the catalog while retaining the source path in failures. */
  def schema(bytes: Array[Byte], path: Path): Schema =
    parseJson[Schema](bytes = bytes, path = path, description = "protocol schema")

  /** Decodes reviewed semantic overrides without performing normalization. */
  def overrides(bytes: Array[Byte], path: Path): Overrides =
    parseJson[Overrides](bytes = bytes, path = path, description = "generation overrides")

  /** Decodes the pinned origin and expected checksum. */
  def provenance(bytes: Array[Byte], path: Path): Provenance =
    parseJson[Provenance](bytes = bytes, path = path, description = "schema provenance")

  private def parseJson[A](bytes: Array[Byte], path: Path, description: String)(using
    JsonValueCodec[A]
  ): A =
    try readFromArray[A](bytes)
    catch
      case error: Exception =>
        throw new IllegalArgumentException(s"Failed to parse $description from $path: ${error.getMessage}", error)
