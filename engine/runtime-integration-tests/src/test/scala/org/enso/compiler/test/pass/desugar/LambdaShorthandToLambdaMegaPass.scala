package org.enso.compiler.test.pass.desugar

import org.enso.compiler.context.{FreshNameSupply, InlineContext, ModuleContext}
import org.enso.compiler.core.CompilerError
import org.enso.compiler.core.ir.{
  CallArgument,
  DefinitionArgument,
  Expression,
  Function,
  IdentifiedLocation,
  Module,
  Name
}
import org.enso.compiler.core.ir.expression.{Application, Case, Operator}
import org.enso.compiler.pass.{IRPass, IRProcessingPass}
import org.enso.compiler.pass.analyse.{
  AliasAnalysis,
  DataflowAnalysis,
  DemandAnalysis,
  TailCall
}
import org.enso.compiler.pass.desugar.{
  ComplexType,
  FunctionBinding,
  GenerateMethodBodies,
  OperatorToFunction,
  SectionsToBinOp
}
import org.enso.compiler.pass.lint.UnusedBindings
import org.enso.compiler.pass.optimise.LambdaConsolidate
import org.enso.compiler.pass.resolve.{
  DocumentationComments,
  IgnoredBindings,
  OverloadsResolution
}

/** Original implementation of [[org.enso.compiler.pass.desugar.LambdaShorthandToLambda]].
  * Now serves as the test verification of the new [[org.enso.compiler.pass.desugar.LambdaShorthandToLambdaMini]] mini pass version.
  *
  * This pass translates `_` arguments at application sites to lambda functions.
  *
  * This pass has no configuration.
  *
  * This pass requires the context to provide:
  *
  * - A [[FreshNameSupply]]
  */
case object LambdaShorthandToLambdaMegaPass extends IRPass {
  override type Metadata = IRPass.Metadata.Empty
  override type Config   = IRPass.Configuration.Default

  override lazy val precursorPasses: Seq[IRProcessingPass] = List(
    ComplexType,
    DocumentationComments,
    FunctionBinding,
    GenerateMethodBodies,
    OperatorToFunction,
    SectionsToBinOp.INSTANCE
  )
  override lazy val invalidatedPasses: Seq[IRProcessingPass] = List(
    AliasAnalysis,
    DataflowAnalysis,
    DemandAnalysis,
    IgnoredBindings,
    LambdaConsolidate,
    OverloadsResolution,
    TailCall.INSTANCE,
    UnusedBindings
  )

  /** Desugars underscore arguments to lambdas for a module.
    *
    * @param ir the Enso IR to process
    * @param moduleContext a context object that contains the information needed
    *                      to process a module
    * @return `ir`, possibly having made transformations or annotations to that
    *         IR.
    */
  override def runModule(
    ir: Module,
    moduleContext: ModuleContext
  ): Module = {
    val new_bindings = ir.bindings.map { case a =>
      a.mapExpressions(
        runExpression(
          _,
          InlineContext(
            moduleContext,
            freshNameSupply = moduleContext.freshNameSupply,
            compilerConfig  = moduleContext.compilerConfig
          )
        )
      )
    }
    ir.copy(bindings = new_bindings)
  }

  /** Desugars underscore arguments to lambdas for an arbitrary expression.
    *
    * @param ir the Enso IR to process
    * @param inlineContext a context object that contains the information needed
    *                      for inline evaluation
    * @return `ir`, possibly having made transformations or annotations to that
    *         IR.
    */
  override def runExpression(
    ir: Expression,
    inlineContext: InlineContext
  ): Expression = {
    val freshNameSupply = inlineContext.freshNameSupply.getOrElse(
      throw new CompilerError(
        "Desugaring underscore arguments to lambdas requires a fresh name " +
        "supply."
      )
    )

    desugarExpression(ir, freshNameSupply)
  }

  // === Pass Internals =======================================================

  /** Performs lambda shorthand desugaring on an arbitrary expression.
    *
    * @param ir the expression to desugar
    * @param freshNameSupply the compiler's fresh name supply
    * @return `ir`, with any lambda shorthand arguments desugared
    */
  def desugarExpression(
    ir: Expression,
    freshNameSupply: FreshNameSupply
  ): Expression = {
    ir.transformExpressions {
      case app: Application    => desugarApplication(app, freshNameSupply)
      case caseExpr: Case.Expr => desugarCaseExpr(caseExpr, freshNameSupply)
      case name: Name          => desugarName(name, freshNameSupply)
    }
  }

  /** Desugars an arbitrary name occurrence, turning isolated occurrences of
    * `_` into the `id` function.
    *
    * @param name the name to desugar
    * @param supply the compiler's fresh name supply
    * @return `name`, desugared where necessary
    */
  private def desugarName(name: Name, supply: FreshNameSupply): Expression = {
    name match {
      case blank: Name.Blank =>
        val newName = supply.newName()

        new Function.Lambda(
          List(
            new DefinitionArgument.Specified(
              name = Name.Literal(
                newName.name,
                isMethod = false,
                null
              ),
              ascribedType       = None,
              defaultValue       = None,
              suspended          = false,
              identifiedLocation = null
            )
          ),
          newName,
          blank.location.orNull
        )
      case _ => name
    }
  }

  /** Desugars lambda shorthand arguments to an arbitrary function application.
    *
    * @param application the function application to desugar
    * @param freshNameSupply the compiler's supply of fresh names
    * @return `application`, with any lambda shorthand arguments desugared
    */
  private def desugarApplication(
    application: Application,
    freshNameSupply: FreshNameSupply
  ): Expression = {
    application match {
      case p: Application.Prefix =>
        // Determine which arguments are lambda shorthand
        val argIsUnderscore = determineLambdaShorthand(p.arguments)

        // Generate a new name for the arg value for each shorthand arg
        val updatedArgs =
          p.arguments
            .zip(argIsUnderscore)
            .map(updateShorthandArg(_, freshNameSupply))
            .map { case s: CallArgument.Specified =>
              s.copy(desugarExpression(s.value, freshNameSupply))
            }

        // Generate a definition arg instance for each shorthand arg
        val defArgs = updatedArgs.zip(argIsUnderscore).map {
          case (arg, isShorthand) => generateDefinitionArg(arg, isShorthand)
        }
        val actualDefArgs = defArgs.collect { case Some(defArg) =>
          defArg
        }

        // Determine whether or not the function itself is shorthand
        val functionIsShorthand = p.function.isInstanceOf[Name.Blank]
        val (updatedFn, updatedName) = if (functionIsShorthand) {
          val newFn = freshNameSupply
            .newName()
            .copy(
              location    = p.function.location,
              passData    = p.function.passData,
              diagnostics = p.function.diagnostics
            )
          val newName = newFn.name
          (newFn, Some(newName))
        } else {
          val newFn = desugarExpression(p.function, freshNameSupply)
          (newFn, None)
        }

        val processedApp = p.copy(
          function  = updatedFn,
          arguments = updatedArgs
        )

        // Wrap the app in lambdas from right to left, 1 lambda per shorthand
        // arg
        val appResult =
          actualDefArgs.foldRight(processedApp: Expression)((arg, body) =>
            new Function.Lambda(List(arg), body, null)
          )

        // If the function is shorthand, do the same
        val resultExpr = if (functionIsShorthand) {
          new Function.Lambda(
            List(
              new DefinitionArgument.Specified(
                Name
                  .Literal(
                    updatedName.get,
                    isMethod = false,
                    p.function.location.orNull
                  ),
                None,
                None,
                suspended = false,
                null
              )
            ),
            appResult,
            null
          )
        } else appResult

        resultExpr match {
          case lam: Function.Lambda => lam.copy(location = p.location)
          case result               => result
        }
      case f: Application.Force =>
        f.copyWithTarget(desugarExpression(f.target, freshNameSupply))
      case vector: Application.Sequence =>
        var bindings: List[Name] = List()
        val newItems = vector.items.map {
          case blank: Name.Blank =>
            val name = freshNameSupply
              .newName()
              .copy(
                location    = blank.location,
                passData    = blank.passData,
                diagnostics = blank.diagnostics
              )
            bindings ::= name
            name
          case it => desugarExpression(it, freshNameSupply)
        }
        val newVec = vector.copyWithItems(newItems)
        val locWithoutId =
          newVec.location.map(l => new IdentifiedLocation(l.location()))
        bindings.foldLeft(newVec: Expression) { (body, bindingName) =>
          val defArg = new DefinitionArgument.Specified(
            bindingName,
            ascribedType       = None,
            defaultValue       = None,
            suspended          = false,
            identifiedLocation = null
          )
          new Function.Lambda(List(defArg), body, locWithoutId.orNull)
        }
      case tSet: Application.Typeset =>
        tSet.copyWithExpression(
          tSet.expression.map(desugarExpression(_, freshNameSupply))
        )
      case _: Operator =>
        throw new CompilerError(
          "Operators should be desugared by the point of underscore " +
          "to lambda conversion."
        )
    }
  }

  /** Determines, positionally, which of the application arguments are lambda
    * shorthand arguments.
    *
    * @param args the application arguments
    * @return a list containing `true` for a given position if the arg in that
    *         position is lambda shorthand, otherwise `false`
    */
  private def determineLambdaShorthand(
    args: List[CallArgument]
  ): List[Boolean] = {
    args.map { arg =>
      arg.value match {
        case _: Name.Blank => true
        case _             => false
      }
    }
  }

  /** Generates a new name to replace a shorthand argument, as well as the
    * corresponding definition argument.
    *
    * @param argAndIsShorthand the arguments, and whether or not the argument in
    *                          the corresponding position is shorthand
    * @return the above described pair for a given position if the argument in
    *         a given position is shorthand, otherwise [[None]].
    */
  private def updateShorthandArg(
    argAndIsShorthand: (CallArgument, Boolean),
    freshNameSupply: FreshNameSupply
  ): CallArgument = {
    val arg         = argAndIsShorthand._1
    val isShorthand = argAndIsShorthand._2

    arg match {
      case s: CallArgument.Specified =>
        if (isShorthand) {
          val newName = freshNameSupply
            .newName()
            .copy(
              location    = s.value.location,
              passData    = s.value.passData,
              diagnostics = s.value.diagnostics
            )

          s.copy(value = newName)
        } else s
    }
  }

  /** Generates a corresponding definition argument to a call argument that was
    * previously lambda shorthand.
    *
    * @param arg the argument to generate a corresponding def argument to
    * @param isShorthand whether or not `arg` was shorthand
    * @return a corresponding definition argument if `arg` `isShorthand`,
    *         otherwise [[None]]
    */
  private def generateDefinitionArg(
    arg: CallArgument,
    isShorthand: Boolean
  ): Option[DefinitionArgument] = {
    if (isShorthand) {
      arg match {
        case specified: CallArgument.Specified =>
          // Note [Safe Casting to Name.Literal]
          val defArgName =
            Name.Literal(
              specified.value.asInstanceOf[Name.Literal].name,
              isMethod = false,
              null
            )

          Some(
            new DefinitionArgument.Specified(
              defArgName,
              None,
              None,
              suspended = false,
              null,
              specified.passData.duplicate,
              specified.diagnosticsCopy
            )
          )
      }
    } else None
  }

  /* Note [Safe Casting to Name.Literal]
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   * This cast is entirely safe here as, by construction in
   * `updateShorthandArg`, any arg for which `isShorthand` is true has its
   * value as an `Name.Literal`.
   */

  /** Performs desugaring of lambda shorthand arguments in a case expression.
    *
    * In the case where a user writes `case _ of`, this gets converted into a
    * lambda (`x -> case x of`).
    *
    * @param caseExpr the case expression to desugar
    * @param freshNameSupply the compiler's supply of fresh names
    * @return `caseExpr`, with any lambda shorthand desugared
    */
  private def desugarCaseExpr(
    caseExpr: Case.Expr,
    freshNameSupply: FreshNameSupply
  ): Expression = {
    val newBranches = caseExpr.branches.map(
      _.mapExpressions(expr => desugarExpression(expr, freshNameSupply))
    )

    caseExpr.scrutinee match {
      case nameBlank: Name.Blank =>
        val scrutineeName =
          freshNameSupply
            .newName()
            .copy(
              location    = nameBlank.location,
              passData    = nameBlank.passData,
              diagnostics = nameBlank.diagnostics
            )

        val lambdaArg = new DefinitionArgument.Specified(
          scrutineeName.copy(id = null),
          None,
          None,
          suspended = false,
          null
        )

        val newCaseExpr = caseExpr.copy(
          scrutinee = scrutineeName,
          branches  = newBranches
        )

        new Function.Lambda(
          caseExpr,
          List(lambdaArg),
          newCaseExpr,
          caseExpr.location.orNull
        )
      case x =>
        caseExpr.copy(
          scrutinee = desugarExpression(x, freshNameSupply),
          branches  = newBranches
        )
    }
  }
}
