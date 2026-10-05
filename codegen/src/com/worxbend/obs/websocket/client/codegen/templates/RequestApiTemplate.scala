package com.worxbend.obs.websocket.client.codegen.templates

import com.worxbend.obs.websocket.client.codegen.model.{RequestCategory, RequestDefinition}
import com.worxbend.obs.websocket.client.codegen.schema.Provenance
import SourceFragments.*
import FieldFragments.{parameters, arguments}

/** Source layout for protocol-only category facades; the error parameter prescribes no effect runtime.
  *
  * Produces `RequestApi.scala` with one executor trait and a facade class per normalized category. Each facade method
  * constructs the same typed request emitted by RequestTemplate and delegates to its owning executor. Session lifetime,
  * transport, retries, and error policy remain responsibilities of the executor implementation.
  */
private[codegen] object RequestApiTemplate:
  /** Categories contain the same normalized requests used by [[RequestTemplate]]. */
  final case class Context(categories: List[RequestCategory], provenance: Provenance)

  /** Renders facade ownership and delegation without repeating schema normalization. */
  def render(context: Context): String =
    val accessors = context.categories
      .map(category => s"  val ${category.name}: ${category.className}[E] = new ${category.className}(this)\n")
      .mkString
    val categories = context.categories.map(renderCategory).mkString("\n")
    val fileHeader = header(base, context.provenance, withImport = false)
    s"""$fileHeader/** Discoverable categories for the full pinned catalog. Implementations retain ownership of request policy. */
       |trait RequestApi[E]:
       |  def request[A](request: Request[A]): Either[E, A]
       |$accessors
       |$categories""".stripMargin

  private def renderCategory(category: RequestCategory): String =
    val methods = category.requests.map(renderMethod).mkString("\n")
    s"""final class ${category.className}[E] private[protocol] (executor: RequestApi[E]):
       |$methods""".stripMargin

  private def renderMethod(request: RequestDefinition): String =
    val name = request.name
    val methodParameters = parameters(request.requestFields)
    val methodArguments = arguments(request.requestFields)
    s"""  /** Executes [[$base.requests.$name]] using the owning request executor. */
       |  def ${request.methodName}($methodParameters): Either[E, requests.${name}Response] =
       |    executor.request(requests.$name($methodArguments))
       |""".stripMargin
