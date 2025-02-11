package org.enso.compiler.test.semantic

import org.enso.compiler.core.Implicits.AsMetadata
import org.enso.compiler.core.ir.{Expression, Module, Type}
import org.enso.compiler.core.ir
import org.enso.compiler.core.ir.module.scope.definition
import org.enso.compiler.core.ir.`type`
import org.enso.compiler.pass.resolve.{TypeNames, TypeSignatures}
import org.enso.interpreter.runtime.EnsoContext
import org.enso.interpreter.test.InterpreterContext
import org.enso.pkg.QualifiedName
import org.enso.common.{LanguageInfo, MethodNames, RuntimeOptions}
import org.enso.editions.LibraryName
import org.enso.test.utils.{ProjectUtils, SourceModule}
import org.enso.testkit.WithTemporaryDirectory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.{MatchResult, Matcher}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.io.ByteArrayOutputStream
import scala.jdk.CollectionConverters.SetHasAsJava

trait TypeMatchers {
  sealed trait Sig {
    def ->:(that: Sig): Sig = this match {
      case Fn(args, r) => Fn(that :: args, r)
      case _           => Fn(List(that), this)
    }

    def |(that: Sig): Sig = {
      def toUnion(sig: Sig): List[Sig] = sig match {
        case Union(items) => items
        case other        => List(other)
      }
      Union(toUnion(this) ++ toUnion(that))
    }

    def inCtx(context: Name): Sig = In(this, context)
  }

  case class Name(name: String)               extends Sig
  case class AnyQualName(name: QualifiedName) extends Sig
  case class Fn(args: List[Sig], result: Sig) extends Sig
  case class Union(items: List[Sig])          extends Sig
  case class In(item: Sig, context: Name)     extends Sig

  implicit def fromString(str: String): Sig = {
    if (str.contains(".")) {
      AnyQualName(QualifiedName.fromString(str))
    } else { Name(str) }
  }

  def typeAs(sig: Sig): TypeMatcher = TypeMatcher(sig)

  val Input = Name("Input")

  case class TypeMatcher(sig: Sig) extends Matcher[Expression] {
    private def findInequalityWitness(
      sig: Sig,
      expr: Expression
    ): Option[(Sig, Expression, String)] = (sig, expr) match {
      case (Name(n), t: ir.Name.Literal) =>
        Option.when(n != t.name)((sig, expr, "names do not match"))
      case (AnyQualName(n), _) =>
        val meta = expr.getMetadata(TypeNames)
        meta match {
          case None =>
            Some((sig, expr, "the expression does not have a resolution"))
          case Some(resolution) =>
            if (resolution.target.qualifiedName == n) {
              None
            } else {
              Some(
                (
                  sig,
                  expr,
                  s"The resolution is ${resolution.target.qualifiedName}, but expected ${n}"
                )
              )
            }
        }
      case (Fn(args, res), t: Type.Function) =>
        if (args.length != t.args.length) {
          Some((sig, expr, "arity does not match"))
        } else {
          args
            .lazyZip(t.args)
            .flatMap(findInequalityWitness)
            .headOption
            .orElse(findInequalityWitness(res, t.result))
        }
      case (Union(items), t: `type`.Set.Union) =>
        if (items.length != t.operands.length) {
          Some((sig, expr, "number of items does not match"))
        } else {
          items.lazyZip(t.operands).flatMap(findInequalityWitness).headOption
        }
      case (In(typed, context), Type.Context(irTyped, irContext, _, _)) =>
        findInequalityWitness(typed, irTyped).orElse(
          findInequalityWitness(context, irContext)
        )

      case _ => Some((sig, expr, "constructors are incompatible"))
    }

    override def apply(left: Expression): MatchResult = {
      findInequalityWitness(sig, left) match {
        case Some((s, t, r)) =>
          MatchResult(
            matches = false,
            s"""
               |${left.showCode()}
               |($left)
               |does not match
               |$sig.
               |Analysis:
               |  sub-expression
               |    ${t.showCode()}
               |    ($t)
               |  did not match fragment $s
               |  because $r.
               |
               |""".stripMargin,
            "The type matched the matcher, but it should not."
          )
        case _ => MatchResult(matches = true, "", "")
      }
    }
  }
}

class TypeSignaturesTest
    extends AnyWordSpecLike
    with Matchers
    with TypeMatchers
    with WithTemporaryDirectory
    with BeforeAndAfterAll {

  private var ctx: InterpreterContext        = _
  private var langCtx: EnsoContext           = _
  private var devNull: ByteArrayOutputStream = new ByteArrayOutputStream()

  override def afterAll(): Unit = {
    ctx.close()
    devNull.close()
    devNull = null
    ctx     = null
    langCtx = null
  }

  private val libName: LibraryName = LibraryName("local", "My_Lib")

  implicit private class PreprocessModule(code: String) {
    def preprocessModule: Module = {
      val utilMod = new SourceModule(
        QualifiedName.simpleName("Util"),
        """
          |type Util_1
          |type Util_2
          |
          |type Util_Sum
          |    Util_Sum_1
          |    Util_Sum_2
          |""".stripMargin
      )
      val testMod = new SourceModule(QualifiedName.simpleName("Test"), code)
      ProjectUtils.createProject(
        libName.name,
        Set(utilMod, testMod).asJava,
        getTestDirectory
      )
      ctx = new InterpreterContext(bldr =>
        bldr
          .option(
            RuntimeOptions.PROJECT_ROOT,
            getTestDirectory.toFile.getAbsolutePath
          )
          .logHandler(devNull)
      )
      langCtx = ctx
        .ctx()
        .getBindings(LanguageInfo.ID)
        .invokeMember(MethodNames.TopScope.LEAK_CONTEXT)
        .asHostObject[EnsoContext]()
      val pkgWasLoaded =
        langCtx.getPackageRepository.ensurePackageIsLoaded(libName)
      pkgWasLoaded.isRight shouldBe true

      val testModName = QualifiedName.fromString(libName.toString + ".Test")
      val module =
        langCtx.getPackageRepository.getLoadedModule(testModName.toString)
      module shouldBe defined
      val compilerRes = langCtx.getCompiler.run(module.get)
      compilerRes.compiledModules.isEmpty shouldBe false
      module.get.getIr
    }
  }

  private def getSignature(
    module: Module,
    methodName: String
  ): Expression = {
    val m = module.bindings.find {
      case m: definition.Method =>
        m.methodName.name == methodName
      case _ => false
    }.get
    m.unsafeGetMetadata(
      TypeSignatures,
      s"expected a type signature on method $methodName"
    ).signature
  }

  "Type Signatures" should {
    "be parsed in a simple scenario" in {
      val code =
        """
          |type Text
          |type Number
          |
          |foo : Text -> Number
          |foo a = 42""".stripMargin
      val module = code.preprocessModule
      getSignature(module, "foo") should typeAs("Text" ->: "Number")
    }

    "resolve locally defined names" in {
      val code =
        """
          |type A
          |type B
          |
          |type C
          |    X
          |    D
          |
          |foo : A -> B -> C -> C -> A
          |foo a = 42""".stripMargin
      val module = code.preprocessModule
      getSignature(module, "foo") should typeAs(
        "local.My_Lib.Test.A" ->: "local.My_Lib.Test.B" ->: "local.My_Lib.Test.C" ->: "local.My_Lib.Test.C" ->: "local.My_Lib.Test.A"
      )
    }

    "resolve imported names" in {
      val code =
        """
          |from project.Util import all
          |
          |foo : Util_1 -> Util_2
          |foo a = 23
          |""".stripMargin
      val module = code.preprocessModule
      getSignature(module, "foo") should typeAs(
        "local.My_Lib.Util.Util_1" ->: "local.My_Lib.Util.Util_2"
      )
    }

    "resolve imported union type names" in {
      val code =
        """
          |from project.Util import all
          |
          |foo : Util_Sum -> Util_2
          |foo a = 23
          |""".stripMargin
      val module = code.preprocessModule
      getSignature(module, "foo") should typeAs(
        "local.My_Lib.Util.Util_Sum" ->: "local.My_Lib.Util.Util_2"
      )
    }

    "resolve anonymous sum types" in {
      val code =
        """from project.Util import all
          |
          |type Foo
          |
          |baz : Foo | Util_2 | Util_Sum -> Foo
          |baz a = 123
          |""".stripMargin
      val module = code.preprocessModule
      getSignature(module, "baz") should typeAs(
        ("local.My_Lib.Test.Foo" | "local.My_Lib.Util.Util_2" | "local.My_Lib.Util.Util_Sum") ->: "local.My_Lib.Test.Foo"
      )
    }

    "resolve execution contexts" in {
      val code =
        """
          |type A
          |type B
          |type C
          |type Input
          |
          |foo : A -> B -> C in Input
          |foo a b = c
          |""".stripMargin
      getSignature(code.preprocessModule, "foo") should typeAs(
        "A" ->: "B" ->: ("C" inCtx Input)
      )
    }
  }
}
