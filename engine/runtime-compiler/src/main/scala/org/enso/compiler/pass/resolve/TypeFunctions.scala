package org.enso.compiler.pass.resolve

import org.enso.compiler.context.{InlineContext, ModuleContext}
import org.enso.compiler.core.Implicits.AsMetadata
import org.enso.compiler.core.IR
import org.enso.compiler.core.ir.{
  `type`,
  CallArgument,
  Expression,
  IdentifiedLocation,
  Module,
  Name,
  Type
}
import org.enso.compiler.core.ir.MetadataStorage._
import org.enso.compiler.core.ir.expression.Error
import org.enso.compiler.core.CompilerError
import org.enso.compiler.core.ir.expression.{Application, Operator}
import org.enso.compiler.pass.IRPass
import org.enso.compiler.pass.IRProcessingPass
import org.enso.compiler.pass.analyse._
import org.enso.compiler.pass.desugar.{
  LambdaShorthandToLambda,
  OperatorToFunction,
  SectionsToBinOp
}
import org.enso.compiler.pass.lint.UnusedBindings

import scala.annotation.unused

/** This pass is responsible for lifting applications of type functions such as
  * `:` and `in` and `!` into their specific IR nodes.
  *
  * This pass requires the context to provide:
  *
  * - Nothing
  */
case object TypeFunctions extends IRPass {
  override type Metadata = IRPass.Metadata.Empty
  override type Config   = IRPass.Configuration.Default

  override lazy val precursorPasses: Seq[IRProcessingPass] = List(
    IgnoredBindings,
    LambdaShorthandToLambda,
    OperatorToFunction,
    SectionsToBinOp.INSTANCE
  )

  override lazy val invalidatedPasses: Seq[IRProcessingPass] = List(
    AliasAnalysis,
    CachePreferenceAnalysis,
    DataflowAnalysis,
    DemandAnalysis,
    org.enso.compiler.pass.analyse.TailCall.INSTANCE,
    UnusedBindings
  )

  /** Performs typing function resolution on a module.
    *
    * @param ir the Enso IR to process
    * @param moduleContext a context object that contains the information needed
    *                      to process a module
    * @return `ir`, possibly having made transformations or annotations to that
    *         IR.
    */
  override def runModule(
    ir: Module,
    @unused moduleContext: ModuleContext
  ): Module = {
    val new_bindings = ir.bindings.map(_.mapExpressions(resolveExpression))
    ir.copy(bindings = new_bindings)
  }

  /** Performs typing function resolution on an expression.
    *
    * @param ir the Enso IR to process
    * @param inlineContext a context object that contains the information needed
    *                      for inline evaluation
    * @return `ir`, possibly having made transformations or annotations to that
    *         IR.
    */
  override def runExpression(
    ir: Expression,
    @unused inlineContext: InlineContext
  ): Expression =
    ir.transformExpressions { case a =>
      resolveExpression(a)
    }

  // === Pass Internals =======================================================

  /** The names of the known typing functions. */
  private val knownTypingFunctions: Set[String] = Set(
    Type.Ascription.name,
    Type.Context.name,
    Type.Error.name,
    `type`.Set.Concat.name,
    `type`.Set.Subsumption.name,
    `type`.Set.Equality.name,
    `type`.Set.Union.name,
    `type`.Set.Intersection.name
  )

  /** Performs resolution of typing functions in an arbitrary expression.
    *
    * @param expr the expression to perform resolution in
    * @return `expr`, with any typing functions resolved
    */
  def resolveExpression(expr: Expression): Expression = {
    expr.transformExpressions { case app: Application =>
      val result = resolveApplication(app)
      app
        .getMetadata(DocumentationComments)
        .map(doc =>
          result.updateMetadata(new MetadataPair(DocumentationComments, doc))
        )
        .getOrElse(result)
    }
  }

  /** Performs resolution of typing functions in an application.
    *
    * @param app the application to perform resolution in
    * @return `app`, with any typing functions resolved
    */
  private def resolveApplication(app: Application): Expression = {
    app match {
      case pre: Application.Prefix =>
        pre.function match {
          case name: Name if name.name == `type`.Set.Union.name =>
            val members = flattenUnion(app).map(resolveExpression)
            `type`.Set.Union(members, app.identifiedLocation())
          case name: Name if knownTypingFunctions.contains(name.name) =>
            resolveKnownFunction(
              name,
              pre.arguments,
              pre.identifiedLocation,
              pre
            )
          case _ =>
            pre.copy(
              function  = resolveExpression(pre.function),
              arguments = pre.arguments.map(resolveCallArgument)
            )
        }
      case force: Application.Force =>
        force.copyWithTarget(resolveExpression(force.target))
      case seq: Application.Sequence =>
        seq.copyWithItems(
          seq.items.map(resolveExpression)
        )
      case tSet: Application.Typeset =>
        tSet.copyWithExpression(
          tSet.expression.map(resolveExpression)
        )
      case _: Operator =>
        throw new CompilerError(
          "Operators should not be present during typing functions lifting."
        )
    }
  }

  private def flattenUnion(expr: Expression): List[Expression] = {
    expr match {
      case app: Application.Prefix
          if app.function.isInstanceOf[Name] &&
          app.function.asInstanceOf[Name].name == `type`.Set.Union.name =>
        app.arguments.flatMap(arg => flattenUnion(arg.value))
      case _ => List(expr)
    }
  }

  /** Resolves a known typing function to its IR node.
    *
    * @param name the typing function name
    * @param arguments the application arguments
    * @param location the application location
    * @param originalIR the application to resolve
    * @return the IR node representing `prefix`
    */
  private def resolveKnownFunction(
    name: Name,
    arguments: List[CallArgument],
    location: IdentifiedLocation,
    originalIR: IR
  ): Expression = {
    val expectedNumArgs = 2
    val lengthIsValid   = arguments.length == expectedNumArgs
    val argsAreValid    = arguments.forall(isValidCallArg)

    if (lengthIsValid && argsAreValid) {
      val leftArg  = resolveExpression(arguments.head.value)
      val rightArg = resolveExpression(arguments.last.value)

      name.name match {
        case Type.Ascription.name =>
          Type.Ascription(leftArg, rightArg, None, location)
        case Type.Context.name =>
          Type.Context(leftArg, rightArg, location)
        case Type.Error.name =>
          Type.Error(leftArg, rightArg, location)
        case `type`.Set.Concat.name =>
          `type`.Set.Concat(leftArg, rightArg, location)
        case `type`.Set.Subsumption.name =>
          `type`.Set.Subsumption(leftArg, rightArg, location)
        case `type`.Set.Equality.name =>
          `type`.Set.Equality(leftArg, rightArg, location)
        case `type`.Set.Intersection.name =>
          `type`.Set.Intersection(leftArg, rightArg, location)
      }
    } else {
      Error.InvalidIR(originalIR)
    }
  }

  /** Performs resolution of typing functions in a call argument.
    *
    * @param arg the argument to perform resolution in
    * @return `arg`, with any call arguments resolved
    */
  private def resolveCallArgument(arg: CallArgument): CallArgument = {
    arg match {
      case spec: CallArgument.Specified =>
        spec.copy(
          resolveExpression(spec.value)
        )
    }
  }

  // === Utilities ============================================================

  /** Checks if a call argument is valid for a typing expression.
    *
    * As all typing functions are _operators_ in the source, their arguments
    * must:
    *
    * - Not have a name defined.
    * - Have no suspension info or not be suspended
    *
    * @param arg the argument to check
    * @return `true` if `arg` is valid, otherwise `false`
    */
  private def isValidCallArg(arg: CallArgument): Boolean = {
    arg match {
      case specified: CallArgument.Specified =>
        specified.name.isEmpty
    }
  }
}
