package com.worxbend.obs.websocket.client.lint

import java.nio.file.{Files, Path}
import java.net.URI
import scala.jdk.CollectionConverters.*
import scala.meta.*
import scala.meta.internal.semanticdb as s
import scalafix.v1.*

/** Reads compiler-produced Scala signatures that Scalafix cannot resolve across Scala 3 source files. Only the supplied
  * compilation classpath is searched. Extension definitions are identified from their source ranges because SemanticDB
  * does not mark the hidden receiver in their method signatures.
  */
final private[lint] class SignatureIndex(
  symbols:    Map[String, s.SymbolInformation],
  extensions: Set[String],
):
  def method(symbol: Symbol): Option[SignatureIndex.Method] =
    symbols
      .get(symbol.value)
      .flatMap: info =>
        info.signature match
          case signature: s.MethodSignature if info.language == s.Language.SCALA =>
            val clauses = signature.parameterLists.map: scope =>
              scope.symlinks.toList
                .flatMap(symbols.get)
                .map: parameter =>
                  val repeated = parameter.signature match
                    case s.ValueSignature(_: s.RepeatedType) => true
                    case _                                   => false
                  SignatureIndex.Parameter(name = parameter.displayName, repeated = repeated)
            Some(
              SignatureIndex.Method(
                name      = info.displayName,
                clauses   = clauses.toList,
                extension = extensions.contains(symbol.value),
              )
            )
          case _ => None

  /** Constructors lack an applied-method occurrence in Scala 3 SemanticDB. Only an unambiguous constructor is safe. */
  def constructor(owner: Symbol): Option[Symbol] =
    val matches = symbols.keysIterator.filter(_.startsWith(s"${owner.value}`<init>`(")).filter(_.endsWith(".")).toList
    Option.when(matches.size == 1)(Symbol(sym = matches.head))

private[lint] object SignatureIndex:
  final case class Parameter(name: String, repeated: Boolean)
  final case class Method(name: String, clauses: List[List[Parameter]], extension: Boolean = false)

  def load(paths: List[Path], sourceRoot: Path): SignatureIndex =
    val documents = paths.flatMap: directory =>
      val root = directory.resolve("META-INF/semanticdb")
      if !Files.isDirectory(root) then Nil
      else
        val stream = Files.walk(root)
        try
          stream
            .iterator()
            .asScala
            .filter(path => Files.isRegularFile(path) && path.toString.endsWith(".semanticdb"))
            .flatMap(path => s.TextDocuments.parseFrom(s = Files.readAllBytes(path)).documents)
            .toList
        finally stream.close()
    val symbols = documents.flatMap(_.symbols).filterNot(_.symbol.startsWith("local")).map(info => info.symbol -> info)
    new SignatureIndex(
      symbols    = symbols.toMap,
      extensions = documents.flatMap(document => extensionSymbols(document = document, sourceRoot = sourceRoot)).toSet,
    )

  private def extensionSymbols(document: s.TextDocument, sourceRoot: Path): List[String] =
    val path      = sourceRoot.resolve(URI.create(document.uri).getPath)
    val text      = if document.text.nonEmpty then document.text else Files.readString(path)
    given Dialect = dialects.Scala3
    val tree      = Input.VirtualFile(path = document.uri, value = text).parse[Source].get
    val positions = tree
      .collect:
        case group: Defn.ExtensionGroup =>
          group.body.collect:
            case method: Defn.Def => (method.name.pos.startLine, method.name.pos.startColumn)
      .flatten
      .toSet
    document.occurrences
      .collect:
        case occurrence
            if occurrence.role == s.SymbolOccurrence.Role.DEFINITION &&
              occurrence.range.exists(range => positions.contains((range.startLine, range.startCharacter))) =>
          occurrence.symbol
      .toList
