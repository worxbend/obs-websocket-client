package com.worxbend.obs.websocket.client.codegen

import schema.{Overrides, Provenance, Schema, SchemaNormalizer, SchemaParser}
import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

/** Filesystem boundary for the offline OBS schema compiler.
  *
  * Mill launches this public entry point from `protocol.generatedSources`; the remaining generator types are internal
  * to this build-time module. The pipeline is input bytes -> parsed schema records -> normalized model -> typed
  * template contexts -> relative filenames and contents -> UTF-8 files. Parsing, normalization, and rendering complete
  * before the first output is written. No network access or current timestamp contributes to generation.
  *
  * See `docs/code-generation.md` and the `protocol.generatedSources` task in `build.mill` for checkout paths.
  */
object Generate:
  /** Reads the pinned inputs, checks schema provenance, and writes the complete generated catalog.
    *
    * @param args
    *   exactly four positional paths: schema JSON, output directory, overrides JSON, and provenance JSON. Relative
    *   paths resolve against the process working directory; Mill supplies paths rooted in the checkout and runs this
    *   process from the checkout root. Output filenames resolve directly beneath the output directory.
    * @throws IllegalArgumentException
    *   if argument count, input reads/JSON, checksum, or schema semantics are invalid. Diagnostics retain the input
    *   path or schema owner where available; an uncaught failure terminates the build-time process.
    * @note
    *   Parent output directories are created and matching files are overwritten. This runner does not remove obsolete
    *   files or write transactionally: Mill owns cleanup of its task destination. Use a fresh directory for standalone
    *   runs so removed schema entries cannot leave stale sources behind; a write failure may leave partial output.
    */
  def main(args: Array[String]): Unit =
    require(args.length == 4, "Expected schema path, output directory, overrides path, provenance path")
    val schemaPath    = Path.of(args(0))
    val schemaBytes   = readBytes(path = schemaPath, description = "protocol schema")
    val schema        = SchemaParser.schema(bytes = schemaBytes, path = schemaPath)
    val overridesPath = Path.of(args(2))
    val overrides     =
      SchemaParser.overrides(
        bytes = readBytes(path = overridesPath, description = "generation overrides"),
        path  = overridesPath,
      )
    val provenancePath = Path.of(args(3))
    val provenance     =
      SchemaParser.provenance(
        bytes = readBytes(path = provenancePath, description = "schema provenance"),
        path  = provenancePath,
      )
    verifyChecksum(bytes = schemaBytes, provenance = provenance, provenancePath = provenancePath)
    writeOutputs(
      directory = Path.of(args(1)),
      outputs   = generate(schema = schema, overrides = overrides, provenance = provenance),
    )

  /** Checks the exact input bytes, including whitespace, against the reviewed SHA-256 before rendering. */
  private def verifyChecksum(bytes: Array[Byte], provenance: Provenance, provenancePath: Path): Unit =
    val digest = sha256(bytes = bytes)
    if digest != provenance.sha256 then
      throw new IllegalArgumentException(
        s"Schema checksum $digest does not match ${provenance.sha256} recorded in $provenancePath"
      )

  /** Persists renderer-owned relative paths; directory lifecycle and stale-file cleanup belong to the caller. */
  private def writeOutputs(directory: Path, outputs: Vector[(String, String)]): Unit =
    outputs.foreach: (name, contents) =>
      val target = directory.resolve(name)
      val _      = Files.createDirectories(target.getParent)
      val _      = Files.writeString(target, contents, UTF_8)

  private def readBytes(path: Path, description: String): Array[Byte] =
    try Files.readAllBytes(path)
    catch
      case error: Exception =>
        throw new IllegalArgumentException(s"Failed to read $description from $path: ${error.getMessage}", error)

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(byte => f"${byte & 0xff}%02x").mkString

  /** Normalizes parsed records and renders output entirely in memory, as used by generator fixtures.
    *
    * Unlike [[main]], this entry point cannot check a checksum: parsed records no longer retain the original bytes.
    * Provenance supplies generated headers and upstream documentation links. Semantic validation still runs in
    * [[schema.SchemaNormalizer]], and failures propagate to the caller before any output can be persisted.
    *
    * @return
    *   relative output filename/content pairs in the order defined by [[CodeGenerator.generate]]
    */
  private[codegen] def generate(
    schema:     Schema,
    overrides:  Overrides,
    provenance: Provenance,
  ): Vector[(String, String)] =
    CodeGenerator.generate(
      schema     = SchemaNormalizer.normalize(schema = schema, overrides = overrides),
      provenance = provenance,
    )
