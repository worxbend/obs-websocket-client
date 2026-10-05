package com.worxbend.obs.websocket.client.lint

import scala.meta.*
import java.nio.file.Path
import scalafix.v1.*
import metaconfig.Configured

/** Adds compiler-resolved names to ordinary Scala arguments without reordering expressions. Java APIs, repeated
  * parameters, operators, function application, and control-flow block syntax remain positional.
  */
class NamedArguments private (index: SignatureIndex) extends SemanticRule("NamedArguments"):
  def this() = this(index = SignatureIndex.load(paths = Nil, sourceRoot = Path.of(".")))

  override def withConfiguration(config: Configuration): Configured[Rule] =
    config.conf
      .get[metaconfig.Conf]("NamedArguments")
      .andThen(f = _.get[String]("sourceRoot"))
      .map: root =>
        new NamedArguments(index =
          SignatureIndex.load(paths = config.scalacClasspath.map(_.toNIO), sourceRoot = Path.of(root))
        )

  override def fix(using doc: SemanticDocument): Patch =
    // Local SemanticDB symbols are scoped to a source document; never put them in the global signature index.
    val extensions = doc.tree
      .collect:
        case group: Defn.ExtensionGroup =>
          group.body.collect:
            case definition: Defn.Def => definition.name.symbol
      .flatten
      .toSet
    doc.tree
      .collect:
        case call: Term.Apply
            if call.argClause.mod.isEmpty && call.argClause.tokens.headOption.exists(_.isInstanceOf[Token.LeftParen]) =>
          val (target, clause) = appliedMethod(fun = call.fun)
          val resolved         = call.synthetics.iterator.flatMap(syntheticApply).nextOption().getOrElse(target.symbol)
          if extensions.contains(resolved) then Patch.empty
          else rewrite(symbol = resolved, clause = clause, args = call.argClause.values)
        case init: Init =>
          index
            .constructor(owner = init.tpe.symbol)
            .fold(Patch.empty): constructor =>
              init.argClauses.zipWithIndex
                .collect:
                  case (clause, position) if clause.mod.isEmpty =>
                    rewrite(symbol = constructor, clause = position, args = clause.values)
                .asPatch
      .asPatch

  private def syntheticApply(tree: SemanticTree): Option[Symbol] = tree match
    case ApplyTree(function, _)     => syntheticApply(tree = function)
    case TypeApplyTree(function, _) => syntheticApply(tree = function)
    case SelectTree(_, id) if id.symbol.value.contains(".apply(") || id.symbol.value.contains("#apply(") =>
      Some(id.symbol)
    case id: IdTree if id.symbol.value.contains(".apply(") || id.symbol.value.contains("#apply(") => Some(id.symbol)
    case _                                                                                        => None

  private def appliedMethod(fun: Term): (Term, Int) = fun match
    case application: Term.Apply =>
      val (target, depth) = appliedMethod(fun = application.fun)
      (target, depth + 1)
    case application: Term.ApplyType => appliedMethod(fun = application.fun)
    case _                           => (fun, 0)

  private def method(symbol: Symbol)(using doc: SemanticDocument): Option[SignatureIndex.Method] =
    index
      .method(symbol = symbol)
      .orElse:
        symbol.info.flatMap: info =>
          info.signature match
            case MethodSignature(_, clauses, _) if !info.isJava =>
              Some(
                SignatureIndex.Method(
                  name    = info.displayName,
                  clauses = clauses.map(_.map: parameter =>
                    val repeated = parameter.signature match
                      case ValueSignature(_: scalafix.v1.RepeatedType) => true
                      case _                                           => false
                    SignatureIndex.Parameter(name = parameter.displayName, repeated = repeated)),
                )
              )
            case _ => None

  private def rewrite(symbol: Symbol, clause: Int, args: List[Term])(using doc: SemanticDocument): Patch =
    method(symbol = symbol) match
      case Some(signature)
          if !signature.extension && ordinary(name = signature.name) && !symbol.value.startsWith("scala/Function") =>
        signature.clauses
          .lift(clause)
          .fold(Patch.empty): parameters =>
            if args.size > parameters.size || parameters.exists(_.repeated) then Patch.empty
            else
              args
                .zip(parameters)
                .collect:
                  case (argument, parameter) if eligible(argument = argument, name = parameter.name) =>
                    Patch.addLeft(tree = argument, toAdd = s"${Term.Name(value = parameter.name).syntax} = ").atomic
                .asPatch
      case _ => Patch.empty

  private def ordinary(name: String): Boolean =
    name == "<init>" || name.forall(c => c.isLetterOrDigit || c == '_')

  private def eligible(argument: Term, name: String): Boolean = argument match
    case _: Term.Assign | _: Term.Block | _: Term.Function | _: Term.PartialFunction |
        _: Term.Repeated | _: Term.Placeholder => false
    case _ => name.nonEmpty && !name.contains('$')
