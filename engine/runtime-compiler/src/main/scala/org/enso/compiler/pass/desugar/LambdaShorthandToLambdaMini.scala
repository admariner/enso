package org.enso.compiler.pass.desugar

import org.enso.compiler.context.FreshNameSupply
import org.enso.compiler.core.CompilerError
import org.enso.compiler.core.IR
import org.enso.compiler.core.ir.expression.{Application, Case, Operator}
import org.enso.compiler.core.ir.{
  CallArgument,
  DefinitionArgument,
  Expression,
  Function,
  IdentifiedLocation,
  Name
}
import org.enso.compiler.pass.MiniIRPass

class LambdaShorthandToLambdaMini(
  protected val freshNameSupply: FreshNameSupply,
  private val shouldSkipBlanks: Boolean = false
) extends MiniIRPass {

  override def prepare(
    parent: IR,
    current: Expression
  ): LambdaShorthandToLambdaMini = {
    if (shouldSkipBlanks(parent)) {
      new LambdaShorthandToLambdaMini(freshNameSupply, true)
    } else {
      this
    }
  }

  private def shouldSkipBlanks(parent: IR): Boolean = {
    parent match {
      case app: Application.Prefix =>
        val hasBlankArg = app.arguments.exists {
          case arg: CallArgument.Specified
              if arg.value.isInstanceOf[Name.Blank] =>
            true
          case _ => false
        }
        val hasBlankFn = app.function.isInstanceOf[Name.Blank]
        hasBlankArg || hasBlankFn
      case seq: Application.Sequence =>
        val hasBlankItem = seq.items.exists {
          case _: Name.Blank => true
          case _             => false
        }
        hasBlankItem
      case Case.Expr(_: Name.Blank, _, _, _, _) =>
        true
      case _ => false
    }
  }

  override def transformExpression(ir: Expression): Expression = {
    val newIr = ir match {
      case name: Name          => desugarName(name)
      case app: Application    => desugarApplication(app)
      case caseExpr: Case.Expr => desugarCaseExpr(caseExpr)
      case _                   => ir
    }
    newIr
  }

  /** Desugars an arbitrary name occurrence, turning isolated occurrences of
    * `_` into the `id` function.
    *
    * @param name the name to desugar
    * @return `name`, desugared where necessary
    */
  private def desugarName(name: Name): Expression = {
    name match {
      case blank: Name.Blank if !shouldSkipBlanks =>
        val newName = freshNameSupply.newName()

        new Function.Lambda(
          List(
            new DefinitionArgument.Specified(
              Name.Literal(
                newName.name,
                isMethod = false,
                null
              ),
              None,
              None,
              false,
              null
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
    * @return `application`, with any lambda shorthand arguments desugared
    */
  private def desugarApplication(
    application: Application
  ): Expression = {
    application match {
      case p: Application.Prefix =>
        // Determine which arguments are lambda shorthand
        val argIsUnderscore = determineLambdaShorthand(p.arguments)

        // Generate a new name for the arg value for each shorthand arg
        val updatedArgs =
          p.arguments
            .zip(argIsUnderscore)
            .map(updateShorthandArg)

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
          (p.function, None)
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
                  )
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
          case it => it
        }
        val newVec = vector.copyWithItems(newItems)
        val locWithoutId =
          newVec.location.map(l => new IdentifiedLocation(l.location()))
        bindings.foldLeft(newVec: Expression) { (body, bindingName) =>
          val defArg = new DefinitionArgument.Specified(bindingName)
          new Function.Lambda(List(defArg), body, locWithoutId.orNull)
        }

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
    argAndIsShorthand: (CallArgument, Boolean)
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
    * @return `caseExpr`, with any lambda shorthand desugared
    */
  private def desugarCaseExpr(
    caseExpr: Case.Expr
  ): Expression = {
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
          scrutinee = scrutineeName
        )

        new Function.Lambda(
          caseExpr,
          List(lambdaArg),
          newCaseExpr,
          caseExpr.location.orNull
        )

      case _ => caseExpr
    }
  }
}
