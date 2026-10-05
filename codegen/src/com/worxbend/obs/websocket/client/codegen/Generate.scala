package com.worxbend.obs.websocket.client.codegen

import schema.{Schema, Overrides, Provenance, SchemaParser, SchemaNormalizer}
import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

/** Offline command-line runner. Reads pinned inputs, verifies their checksum, and writes deterministic output. */
object Generate:
  /** Accepts schema path, output directory, overrides path, and provenance path, in that order. */
  def main(args: Array[String]): Unit =
    require(args.length == 4, "Expected schema path, output directory, overrides path, provenance path")
    val schemaPath = Path.of(args(0))
    val schemaBytes = readBytes(schemaPath, "protocol schema")
    val schema = SchemaParser.schema(schemaBytes, schemaPath)
    val overridesPath = Path.of(args(2))
    val overrides =
      SchemaParser.overrides(readBytes(overridesPath, "generation overrides"), overridesPath)
    val provenancePath = Path.of(args(3))
    val provenance =
      SchemaParser.provenance(readBytes(provenancePath, "schema provenance"), provenancePath)
    verifyChecksum(schemaBytes, provenance, provenancePath)
    writeOutputs(Path.of(args(1)), generate(schema, overrides, provenance))

  private def verifyChecksum(bytes: Array[Byte], provenance: Provenance, provenancePath: Path): Unit =
    val digest = sha256(bytes)
    if digest != provenance.sha256 then
      throw new IllegalArgumentException(
        s"Schema checksum $digest does not match ${provenance.sha256} recorded in $provenancePath"
      )

  private def writeOutputs(directory: Path, outputs: Vector[(String, String)]): Unit =
    outputs.foreach: (name, contents) =>
      val target = directory.resolve(name)
      val _ = Files.createDirectories(target.getParent)
      val _ = Files.writeString(target, contents, UTF_8)

  private def readBytes(path: Path, description: String): Array[Byte] =
    try Files.readAllBytes(path)
    catch
      case error: Exception =>
        throw new IllegalArgumentException(s"Failed to read $description from $path: ${error.getMessage}", error)

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(byte => f"${byte & 0xff}%02x").mkString

  /** Pure compatibility entry point used by generator fixtures; performs no filesystem operations. */
  private[codegen] def generate(
      schema: Schema,
      overrides: Overrides,
      provenance: Provenance
  ): Vector[(String, String)] =
    CodeGenerator.generate(SchemaNormalizer.normalize(schema, overrides), provenance)
