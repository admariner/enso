package org.enso.interpreter.test.instrument

import org.apache.commons.io.output.TeeOutputStream
import org.enso.interpreter.runtime.EnsoContext
import org.enso.interpreter.runtime.`type`.{Constants, ConstantsGen, Types}
import org.enso.interpreter.test.Metadata
import org.enso.common.LanguageInfo
import org.enso.common.MethodNames
import org.enso.polyglot.data.TypeGraph
import org.enso.common.RuntimeOptions
import org.enso.polyglot.RuntimeServerInfo
import org.enso.polyglot.debugger.IdExecutionService
import org.enso.polyglot.runtime.Runtime.Api
import org.enso.text.editing.model
import org.enso.text.editing.model.TextEdit
import org.graalvm.polyglot.Context
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.{ByteArrayOutputStream, File}
import java.nio.file.{Files, Paths}
import java.util.UUID

@scala.annotation.nowarn("msg=multiarg infix syntax")
class RuntimeServerTest
    extends AnyFlatSpec
    with Matchers
    with BeforeAndAfterEach {

  // === Test Utilities =======================================================

  var context: TestContext = _

  class TestContext(packageName: String)
      extends InstrumentTestContext(packageName)
      with RuntimeServerTest.TestMain {

    val out: ByteArrayOutputStream    = new ByteArrayOutputStream()
    val logOut: ByteArrayOutputStream = new ByteArrayOutputStream()
    protected val context =
      Context
        .newBuilder(LanguageInfo.ID)
        .allowExperimentalOptions(true)
        .allowAllAccess(true)
        .option(RuntimeOptions.PROJECT_ROOT, pkg.root.getAbsolutePath)
        .option(
          RuntimeOptions.LOG_LEVEL,
          java.util.logging.Level.WARNING.getName
        )
        .option(RuntimeOptions.INTERPRETER_SEQUENTIAL_COMMAND_EXECUTION, "true")
        .option(RuntimeOptions.ENABLE_PROJECT_SUGGESTIONS, "false")
        .option(RuntimeOptions.ENABLE_GLOBAL_SUGGESTIONS, "false")
        .option(RuntimeOptions.ENABLE_EXECUTION_TIMER, "false")
        .option(RuntimeOptions.STRICT_ERRORS, "false")
        .option(
          RuntimeOptions.DISABLE_IR_CACHES,
          InstrumentTestContext.DISABLE_IR_CACHE
        )
        .option(RuntimeServerInfo.ENABLE_OPTION, "true")
        .option(RuntimeOptions.INTERACTIVE_MODE, "true")
        .option(
          RuntimeOptions.LANGUAGE_HOME_OVERRIDE,
          Paths
            .get("../../test/micro-distribution/component")
            .toFile
            .getAbsolutePath
        )
        .option(RuntimeOptions.EDITION_OVERRIDE, "0.0.0-dev")
        .logHandler(new TeeOutputStream(logOut, System.err))
        .out(new TeeOutputStream(out, System.err))
        .serverTransport(runtimeServerEmulator.makeServerTransport)
        .build()

    lazy val languageContext = executionContext.context
      .getBindings(LanguageInfo.ID)
      .invokeMember(MethodNames.TopScope.LEAK_CONTEXT)
      .asHostObject[EnsoContext]

    private def ensureInstrumentsAvailable() = {
      val instruments = context.getEngine.getInstruments
      if (instruments.get(IdExecutionService.INSTRUMENT_ID) == null) {
        throw new IllegalStateException(
          "RuntimeServerTest cannot be initialized: IdExecutionService instrument must be available on module-path"
        )
      }
      if (instruments.get(RuntimeServerInfo.INSTRUMENT_NAME) == null) {
        throw new IllegalStateException(
          "RuntimeServerTest cannot be initialized: RuntimeServer instrument must be available on module-path"
        )
      }
    }

    ensureInstrumentsAvailable()

    def writeMain(contents: String): File =
      Files.write(pkg.mainFile.toPath, contents.getBytes).toFile

    def writeFile(file: File, contents: String): File =
      Files.write(file.toPath, contents.getBytes).toFile

    def writeInSrcDir(moduleName: String, contents: String): File = {
      val file = new File(pkg.sourceDir, s"$moduleName.enso")
      Files.write(file.toPath, contents.getBytes).toFile
    }

    def send(msg: Api.Request): Unit = runtimeServerEmulator.sendToRuntime(msg)

    def consumeOut: List[String] = {
      val result = out.toString
      out.reset()
      result.linesIterator.toList
    }

    def executionComplete(contextId: UUID): Api.Response =
      Api.Response(Api.ExecutionComplete(contextId))
  }

  override protected def beforeEach(): Unit = {
    context = new TestContext("Test")
    context.init()
    val Some(Api.Response(_, Api.InitializedNotification())) = context.receive
  }

  override protected def afterEach(): Unit = {
    if (context != null) {
      context.close()
      context.out.reset()
      context = null
    }
  }

  "RuntimeServer" should "push and pop functions on the stack" in {
    val contents  = context.Main.code
    val mainFile  = context.writeMain(contents)
    val contextId = UUID.randomUUID()
    val requestId = UUID.randomUUID()

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push local item on top of the empty stack
    val invalidLocalItem = Api.StackItem.LocalCall(context.Main.idMainY)
    context.send(
      Api
        .Request(requestId, Api.PushContextRequest(contextId, invalidLocalItem))
    )
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.InvalidStackItemError(contextId))
    )

    // push main
    val item1 = Api.StackItem.ExplicitCall(
      Api.MethodPointer("Enso_Test.Test.Main", "Enso_Test.Test.Main", "main"),
      None,
      Vector()
    )
    context.send(
      Api.Request(requestId, Api.PushContextRequest(contextId, item1))
    )
    context.receiveNIgnoreStdLib(5) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.Main.Update.mainX(contextId, typeChanged = true),
      context.Main.Update.mainY(contextId, typeChanged = true),
      context.Main.Update.mainZ(contextId, typeChanged = true),
      context.executionComplete(contextId)
    )

    // push foo call
    val item2 = Api.StackItem.LocalCall(context.Main.idMainY)
    context.send(
      Api.Request(requestId, Api.PushContextRequest(contextId, item2))
    )
    context.receiveNIgnoreStdLib(4) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.Main.Update.fooY(contextId),
      context.Main.Update.fooZ(contextId),
      context.executionComplete(contextId)
    )

    // push method pointer on top of the non-empty stack
    val invalidExplicitCall = Api.StackItem.ExplicitCall(
      Api.MethodPointer("Enso_Test.Test.Main", "Enso_Test.Test.Main", "main"),
      None,
      Vector()
    )
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(contextId, invalidExplicitCall)
      )
    )
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.InvalidStackItemError(contextId))
    )

    // pop foo call
    context.send(Api.Request(requestId, Api.PopContextRequest(contextId)))
    context.receiveN(4) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PopContextResponse(contextId)),
      context.Main.Update.mainY(contextId, fromCache = true),
      context.Main.Update.mainZ(contextId, fromCache = true),
      context.executionComplete(contextId)
    )

    // pop main
    context.send(Api.Request(requestId, Api.PopContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.PopContextResponse(contextId))
    )

    // pop empty stack
    context.send(Api.Request(requestId, Api.PopContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.EmptyStackError(contextId))
    )
  }

  it should "substitute Nothing when pushing method with unapplied arguments" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"

    val metadata         = new Metadata
    val identityResultId = metadata.addItem(13, 1, "aa")
    val identityCallId   = metadata.addItem(27, 8, "ab")

    val code =
      """identity x = x
        |
        |main =
        |    identity
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveN(3) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(
        contextId,
        identityCallId,
        ConstantsGen.FUNCTION_BUILTIN,
        methodCall = Some(
          Api.MethodCall(
            Api.MethodPointer(moduleName, moduleName, "identity"),
            Vector(0)
          )
        ),
        payload = Api.ExpressionUpdate.Payload.Value(
          functionSchema = Some(
            Api.FunctionSchema(
              Api.MethodPointer(moduleName, moduleName, "identity"),
              Vector(0)
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List()

    // push identity
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.LocalCall(identityCallId)
        )
      )
    )
    context.receiveN(3) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages
        .update(contextId, identityResultId, ConstantsGen.NOTHING_BUILTIN),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List()
  }

  it should "push method with default arguments on top of the stack" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"

    val metadata = new Metadata
    val idFoo    = metadata.addItem(41, 6, "ffff")

    val code =
      """from Standard.Base import all
        |
        |foo x=0 = x + 42
        |
        |main =
        |    IO.println foo
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "foo"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(3) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(
        contextId,
        idFoo,
        ConstantsGen.INTEGER,
        Api.MethodCall(
          Api.MethodPointer(
            "Standard.Base.Data.Numbers",
            ConstantsGen.INTEGER,
            "+"
          )
        )
      ),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List()
  }

  it should "push method with default arguments on the stack" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"

    val metadata  = new Metadata
    val idMain    = metadata.addItem(54, 19)
    val idMainFoo = metadata.addItem(70, 3)

    val code =
      """from Standard.Base import all
        |
        |foo a=0 = a + 1
        |
        |main =
        |    IO.println foo
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(4) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(
        contextId,
        idMainFoo,
        ConstantsGen.INTEGER,
        Api.MethodCall(
          Api.MethodPointer(moduleName, moduleName, "foo"),
          Vector(0)
        )
      ),
      TestMessages.update(contextId, idMain, ConstantsGen.NOTHING),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("1")

    // push foo call
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(contextId, Api.StackItem.LocalCall(idMainFoo))
      )
    )
    context.receiveN(2) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("1")
  }

  it should "send method pointer updates of methods" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"

    val metadata = new Metadata
    val idMain   = metadata.addItem(105, 120, "aa")
    val idMainX  = metadata.addItem(132, 9, "ab")
    val idMainY  = metadata.addItem(150, 3, "ac")
    val idMainM  = metadata.addItem(162, 8, "ad")
    val idMainP  = metadata.addItem(179, 5, "ae")
    val idMainQ  = metadata.addItem(193, 5, "af")
    val idMainF  = metadata.addItem(215, 9, "bb")

    val code =
      """from Standard.Base import all
        |import Enso_Test.Test.A
        |
        |type QuuxT
        |    Quux
        |
        |    foo = 42
        |
        |bar = 7
        |
        |main =
        |    f a b = a + b
        |    x = QuuxT.foo
        |    y = bar
        |    m = A.AT.A x
        |    p = m.foo
        |    q = A.bar
        |    IO.println (f x+y p+q)
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    val aCode =
      """
        |type AT
        |    A un_a
        |
        |    foo self = 11
        |
        |bar = 19
        |""".stripMargin.linesIterator.mkString("\n")
    val aFile = context.writeInSrcDir("A", aCode)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )
    context.send(Api.Request(requestId, Api.OpenFileRequest(aFile, aCode)))
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(9) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(
        contextId,
        idMainX,
        ConstantsGen.INTEGER,
        Api.MethodCall(
          Api.MethodPointer(
            "Enso_Test.Test.Main",
            "Enso_Test.Test.Main.QuuxT",
            "foo"
          )
        )
      ),
      TestMessages.update(
        contextId,
        idMainY,
        ConstantsGen.INTEGER,
        Api.MethodCall(
          Api.MethodPointer("Enso_Test.Test.Main", "Enso_Test.Test.Main", "bar")
        )
      ),
      TestMessages.update(
        contextId,
        idMainM,
        "Enso_Test.Test.A.AT",
        Api.MethodCall(
          Api.MethodPointer("Enso_Test.Test.A", "Enso_Test.Test.A.AT", "A")
        )
      ),
      TestMessages.update(
        contextId,
        idMainP,
        ConstantsGen.INTEGER,
        Api.MethodCall(
          Api.MethodPointer("Enso_Test.Test.A", "Enso_Test.Test.A.AT", "foo")
        )
      ),
      TestMessages.update(
        contextId,
        idMainQ,
        ConstantsGen.INTEGER,
        Api.MethodCall(
          Api.MethodPointer("Enso_Test.Test.A", "Enso_Test.Test.A", "bar")
        )
      ),
      TestMessages.update(contextId, idMainF, ConstantsGen.INTEGER),
      TestMessages.update(contextId, idMain, ConstantsGen.NOTHING),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("79")
  }

  it should "send method pointer updates of constructors" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"

    val metadata = new Metadata
    val idA      = metadata.addItem(47, 3, "aa")
    val idB      = metadata.addItem(59, 6, "ab")
    val idC      = metadata.addItem(70, 7, "ac")

    val code =
      """type T
        |    A
        |    B x
        |    C y z
        |
        |main =
        |    a = T.A
        |    b = T.B 42
        |    T.C a b
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveN(5) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(
        contextId,
        idA,
        "Enso_Test.Test.Main.T",
        Api.MethodCall(
          Api.MethodPointer("Enso_Test.Test.Main", "Enso_Test.Test.Main.T", "A")
        )
      ),
      TestMessages.update(
        contextId,
        idB,
        "Enso_Test.Test.Main.T",
        Api.MethodCall(
          Api.MethodPointer("Enso_Test.Test.Main", "Enso_Test.Test.Main.T", "B")
        )
      ),
      TestMessages.update(
        contextId,
        idC,
        "Enso_Test.Test.Main.T",
        Api.MethodCall(
          Api.MethodPointer("Enso_Test.Test.Main", "Enso_Test.Test.Main.T", "C")
        )
      ),
      context.executionComplete(contextId)
    )
  }

  it should "send method pointer updates of simple_suspended_constructors" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"

    val metadata = new Metadata
    val idA      = metadata.addItem(52, 3, "aa")

    val code =
      """type T
        |    A
        |    B x
        |    C y z
        |
        |main =
        |    a = test T.A
        |    a
        |
        |test ~t:T = t
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    metadata.assertInCode(idA, code, "T.A")

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveN(3) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(
        contextId,
        idA,
        "Enso_Test.Test.Main.T",
        Api.MethodCall(
          Api.MethodPointer("Enso_Test.Test.Main", "Enso_Test.Test.Main.T", "A")
        )
      ),
      context.executionComplete(contextId)
    )
  }

  it should "send method pointer updates of simple_autoscoped_constructors" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"

    val metadata = new Metadata
    val idA      = metadata.addItem(52, 3, "aa")

    val code =
      """type T
        |    A
        |    B x
        |    C y z
        |
        |main =
        |    a = test ..A
        |    a
        |
        |test t:T = t
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    metadata.assertInCode(idA, code, "..A")

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveN(3) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(
        contextId,
        idA,
        "Enso_Test.Test.Main.T",
        Api.MethodCall(
          Api.MethodPointer("Enso_Test.Test.Main", "Enso_Test.Test.Main.T", "A")
        )
      ),
      context.executionComplete(contextId)
    )
  }

  it should "send method pointer updates of autoscope constructors" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"

    val metadata = new Metadata
    val idA      = metadata.addItem(52, 3, "aa")
    val idB      = metadata.addItem(70, 6, "ab")
    val idC      = metadata.addItem(88, 7, "ac")

    val code =
      """type T
        |    A
        |    B x
        |    C y z
        |
        |main =
        |    a = test ..A
        |    b = test (..B 42)
        |    test (..C a b)
        |
        |test t:T = t
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    metadata.assertInCode(idA, code, "..A")
    metadata.assertInCode(idB, code, "..B 42")
    metadata.assertInCode(idC, code, "..C a b")

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveN(5) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(
        contextId,
        idA,
        "Enso_Test.Test.Main.T",
        Api.MethodCall(
          Api.MethodPointer("Enso_Test.Test.Main", "Enso_Test.Test.Main.T", "A")
        )
      ),
      TestMessages.update(
        contextId,
        idB,
        "Enso_Test.Test.Main.T",
        Api.MethodCall(
          Api.MethodPointer("Enso_Test.Test.Main", "Enso_Test.Test.Main.T", "B")
        )
      ),
      TestMessages.update(
        contextId,
        idC,
        "Enso_Test.Test.Main.T",
        Api.MethodCall(
          Api.MethodPointer("Enso_Test.Test.Main", "Enso_Test.Test.Main.T", "C")
        )
      ),
      context.executionComplete(contextId)
    )
  }

  it should "send method pointer updates of when autoscope constructor changes to a value" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"

    val metadata = new Metadata
    val idA      = metadata.addItem(44, 3, "aa")

    val code =
      """type Xyz
        |
        |type T
        |    A
        |
        |main =
        |    a = test Xyz
        |    a
        |
        |test t:(Xyz | T) = t
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    metadata.assertInCode(idA, code, "Xyz")

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveN(3) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(
        contextId,
        idA,
        "Enso_Test.Test.Main.Xyz"
      ),
      context.executionComplete(contextId)
    )

    // Modify the file /Xyz/..A/
    context.send(
      Api.Request(
        Api.EditFileNotification(
          mainFile,
          Seq(
            model.TextEdit(
              model.Range(model.Position(6, 13), model.Position(6, 16)),
              "..A"
            )
          ),
          execute = true,
          idMap   = None
        )
      )
    )

    context.receiveN(3) should contain theSameElementsAs Seq(
      TestMessages.pending(contextId, idA),
      TestMessages.update(
        contextId,
        idA,
        "Enso_Test.Test.Main.T",
        Api.MethodCall(
          Api.MethodPointer("Enso_Test.Test.Main", "Enso_Test.Test.Main.T", "A")
        )
      ),
      context.executionComplete(contextId)
    )

    // Modify the file /..A/Xyz/
    context.send(
      Api.Request(
        Api.EditFileNotification(
          mainFile,
          Seq(
            model.TextEdit(
              model.Range(model.Position(6, 13), model.Position(6, 16)),
              "Xyz"
            )
          ),
          execute = true,
          idMap   = None
        )
      )
    )

    context.receiveN(3) should contain theSameElementsAs Seq(
      TestMessages.pending(contextId, idA),
      TestMessages.update(
        contextId,
        idA,
        "Enso_Test.Test.Main.Xyz"
      ),
      context.executionComplete(contextId)
    )
  }

  it should "send method pointer updates of builtin operators" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"

    val metadata = new Metadata
    val id_x_1   = metadata.addItem(48, 5, "aa")

    val code =
      """from Standard.Base import all
        |
        |main =
        |    x_1 = 3 ^ 4
        |    x_1
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(3) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(
        contextId,
        id_x_1,
        ConstantsGen.INTEGER,
        Api.MethodCall(
          Api.MethodPointer(
            "Standard.Base.Data.Numbers",
            "Standard.Base.Data.Numbers.Integer",
            "^"
          )
        )
      ),
      context.executionComplete(contextId)
    )
  }

  it should "send method pointer updates of partially applied builtin operators" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"

    val metadata = new Metadata
    val id_x_1   = metadata.addItem(48, 5, "aa")
    val id_x_2   = metadata.addItem(64, 7, "ab")

    val code =
      """from Standard.Base import all
        |
        |main =
        |    x_1 = "4" +
        |    x_2 = x_1 "2"
        |    x_2
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    val textPlusMethodPointer = Api.MethodPointer(
      "Standard.Base.Data.Text",
      "Standard.Base.Data.Text.Text",
      "+"
    )
    context.receiveNIgnoreStdLib(4) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(
        contextId,
        id_x_1,
        ConstantsGen.FUNCTION,
        methodCall = Some(Api.MethodCall(textPlusMethodPointer, Vector(1))),
        payload = Api.ExpressionUpdate.Payload.Value(
          None,
          Some(Api.FunctionSchema(textPlusMethodPointer, Vector(1)))
        )
      ),
      TestMessages.update(
        contextId,
        id_x_2,
        ConstantsGen.TEXT,
        Api.MethodCall(textPlusMethodPointer)
      ),
      context.executionComplete(contextId)
    )
  }

  it should "send method pointer updates of partially applied constructors" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"

    val metadata = new Metadata
    val id_x_0   = metadata.addItem(35, 3, "aa")
    val id_x_1   = metadata.addItem(49, 5, "ab")
    val id_x_2   = metadata.addItem(65, 5, "ac")

    val code =
      """type T
        |    A x y
        |
        |main =
        |    x_0 = T.A
        |    x_1 = x_0 1
        |    x_2 = x_1 2
        |    x_2
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveN(5) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(
        contextId,
        id_x_0,
        ConstantsGen.FUNCTION_BUILTIN,
        methodCall = Some(
          Api.MethodCall(Api.MethodPointer(moduleName, s"$moduleName.T", "A"))
        ),
        payload = Api.ExpressionUpdate.Payload.Value(
          functionSchema = Some(
            Api.FunctionSchema(
              Api.MethodPointer(moduleName, s"$moduleName.T", "A"),
              Vector(0, 1)
            )
          )
        )
      ),
      TestMessages.update(
        contextId,
        id_x_1,
        ConstantsGen.FUNCTION_BUILTIN,
        methodCall = Some(
          Api.MethodCall(
            Api.MethodPointer(moduleName, s"$moduleName.T", "A"),
            Vector(1)
          )
        ),
        payload = Api.ExpressionUpdate.Payload.Value(
          functionSchema = Some(
            Api.FunctionSchema(
              Api.MethodPointer(moduleName, s"$moduleName.T", "A"),
              Vector(1)
            )
          )
        )
      ),
      TestMessages.update(
        contextId,
        id_x_2,
        s"$moduleName.T",
        Api.MethodCall(Api.MethodPointer(moduleName, s"$moduleName.T", "A"))
      ),
      context.executionComplete(contextId)
    )
  }

  it should "send error updates for partially applied autoscope constructors" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"

    val metadata = new Metadata
    val id_x_0   = metadata.addItem(40, 3, "aa")
    val id_x_1   = metadata.addItem(60, 5, "ab")

    val code =
      """type T
        |    A x y
        |
        |main =
        |    x_0 = test ..A
        |    x_1 = test (..A 1)
        |    T.A x_0 x_1
        |
        |test t:T = t
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    metadata.assertInCode(id_x_0, code, "..A")
    metadata.assertInCode(id_x_1, code, "..A 1")

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveN(3) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(
        contextId,
        id_x_0,
        ConstantsGen.FUNCTION_BUILTIN,
        methodCall = Some(
          Api.MethodCall(
            Api.MethodPointer(moduleName, s"$moduleName.T", "A"),
            Vector(0, 1)
          )
        ),
        payload = Api.ExpressionUpdate.Payload.Value(
          functionSchema = Some(
            Api.FunctionSchema(
              Api.MethodPointer(moduleName, s"$moduleName.T", "A"),
              Vector(0, 1)
            )
          )
        )
      ),
      Api.Response(
        Api.ExecutionFailed(
          contextId,
          Api.ExecutionResult.Diagnostic.error(
            "Type_Error.Error",
            Some(mainFile),
            Some(model.Range(model.Position(8, 0), model.Position(8, 12))),
            None,
            Vector(
              Api.StackTraceElement(
                "Main.test",
                Some(mainFile),
                Some(model.Range(model.Position(8, 0), model.Position(8, 12))),
                None
              ),
              Api.StackTraceElement(
                "Main.main",
                Some(mainFile),
                Some(model.Range(model.Position(4, 10), model.Position(4, 18))),
                None
              )
            )
          )
        )
      )
    )
  }

  it should "send method pointer updates of partially applied static method returning a method" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"

    val metadata = new Metadata("import Standard.Base.Data.Numbers\n\n")
    val id_x_1   = metadata.addItem(17, 9, "aa")
    val id_x_2   = metadata.addItem(37, 5, "ab")

    val code =
      """main =
        |    x_1 = func1 1 2
        |    x_2 = x_1 3
        |    x_2
        |
        |func1 x = func2 x
        |func2 x y z = x + y + z
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(4) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(
        contextId,
        id_x_1,
        ConstantsGen.FUNCTION_BUILTIN,
        methodCall = Some(
          Api.MethodCall(
            Api.MethodPointer(moduleName, moduleName, "func1")
          )
        ),
        payload = Api.ExpressionUpdate.Payload.Value(
          functionSchema = Some(
            Api.FunctionSchema(
              Api.MethodPointer(moduleName, moduleName, "func2"),
              Vector(2)
            )
          )
        )
      ),
      TestMessages.update(
        contextId,
        id_x_2,
        ConstantsGen.INTEGER,
        Api.MethodCall(
          Api
            .MethodPointer(moduleName, moduleName, "func2"),
          Vector()
        )
      ),
      context.executionComplete(contextId)
    )
  }

  it should "send method pointer updates of partially applied type method returning a method" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"

    val metadata = new Metadata("import Standard.Base.Data.Numbers\n\n")
    val id_x_1   = metadata.addItem(17, 9, "aa")
    val id_x_2   = metadata.addItem(37, 5, "ab")
    val id_x_3   = metadata.addItem(53, 7, "ac")

    val code =
      """main =
        |    x_1 = T.A.func1
        |    x_2 = x_1 1
        |    x_3 = x_2 2 3
        |    x_3
        |
        |type T
        |    A
        |    func1 self x = self.func2 x
        |    func2 self x y z = x + y + z
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(5) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(
        contextId,
        id_x_1,
        ConstantsGen.FUNCTION_BUILTIN,
        methodCall = Some(
          Api.MethodCall(
            Api.MethodPointer(moduleName, s"$moduleName.T", "func1"),
            Vector(1)
          )
        ),
        payload = Api.ExpressionUpdate.Payload.Value(
          functionSchema = Some(
            Api.FunctionSchema(
              Api.MethodPointer(moduleName, s"$moduleName.T", "func1"),
              Vector(1)
            )
          )
        )
      ),
      TestMessages.update(
        contextId,
        id_x_2,
        ConstantsGen.FUNCTION_BUILTIN,
        methodCall = Some(
          Api.MethodCall(
            Api.MethodPointer(moduleName, s"$moduleName.T", "func1")
          )
        ),
        payload = Api.ExpressionUpdate.Payload.Value(
          functionSchema = Some(
            Api.FunctionSchema(
              Api.MethodPointer(moduleName, s"$moduleName.T", "func2"),
              Vector(2, 3)
            )
          )
        )
      ),
      TestMessages.update(
        contextId,
        id_x_3,
        ConstantsGen.INTEGER,
        Api.MethodCall(
          Api.MethodPointer(moduleName, s"$moduleName.T", "func2")
        )
      ),
      context.executionComplete(contextId)
    )
  }

  it should "send method pointer updates of partially applied static methods defined on type" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"

    val metadata = new Metadata("import Standard.Base.Data.Numbers\n\n")
    val id_x1_1  = metadata.addItem(18, 7, "aa")
    val id_x1_2  = metadata.addItem(37, 6, "ab")
    val id_x1    = metadata.addItem(53, 6, "ac")

    val code =
      """main =
        |    x1_1 = T.func1
        |    x1_2 = x1_1 1
        |    x1 = x1_2 2
        |    x1
        |
        |type T
        |    A
        |
        |    func1 x y = x + y
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(5) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(
        contextId,
        id_x1_1,
        ConstantsGen.FUNCTION_BUILTIN,
        methodCall = Some(
          Api.MethodCall(
            Api.MethodPointer(
              "Enso_Test.Test.Main",
              "Enso_Test.Test.Main.T",
              "func1"
            ),
            Vector(0, 1)
          )
        ),
        payload = Api.ExpressionUpdate.Payload.Value(
          functionSchema = Some(
            Api.FunctionSchema(
              Api.MethodPointer(moduleName, s"$moduleName.T", "func1"),
              Vector(0, 1)
            )
          )
        )
      ),
      TestMessages.update(
        contextId,
        id_x1_2,
        ConstantsGen.FUNCTION_BUILTIN,
        methodCall = Some(
          Api.MethodCall(
            Api.MethodPointer(
              "Enso_Test.Test.Main",
              "Enso_Test.Test.Main.T",
              "func1"
            ),
            Vector(1)
          )
        ),
        payload = Api.ExpressionUpdate.Payload.Value(
          functionSchema = Some(
            Api.FunctionSchema(
              Api.MethodPointer(moduleName, s"$moduleName.T", "func1"),
              Vector(1)
            )
          )
        )
      ),
      TestMessages.update(
        contextId,
        id_x1,
        ConstantsGen.INTEGER,
        Api.MethodCall(
          Api.MethodPointer(
            "Enso_Test.Test.Main",
            "Enso_Test.Test.Main.T",
            "func1"
          )
        )
      ),
      context.executionComplete(contextId)
    )
  }

  it should "send method pointer updates of partially applied static methods without application" in {
    pending
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"

    val metadata = new Metadata("import Standard.Base.Data.Numbers\n\n")
    val id_x1_1  = metadata.addItem(18, 7, "aa")
    val id_x1_2  = metadata.addItem(37, 4, "ab")
    val id_x1    = metadata.addItem(51, 8, "ac")

    val code =
      """main =
        |    x1_1 = T.func1
        |    x1_2 = x1_1
        |    x1 = x1_2 1 2
        |    x1
        |
        |type T
        |    A
        |
        |    func1 x y = x + y
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(5) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(
        contextId,
        id_x1_1,
        ConstantsGen.FUNCTION_BUILTIN,
        Api.MethodCall(
          Api.MethodPointer(
            "Enso_Test.Test.Main",
            "Enso_Test.Test.Main.T",
            "func1"
          ),
          Vector(0, 1)
        )
      ),
      TestMessages.update(
        contextId,
        id_x1_2,
        ConstantsGen.FUNCTION_BUILTIN,
        // the method call is missing
        Api.MethodCall(
          Api.MethodPointer(
            "Enso_Test.Test.Main",
            "Enso_Test.Test.Main.T",
            "func1"
          ),
          Vector(1, 2)
        )
      ),
      TestMessages.update(
        contextId,
        id_x1,
        ConstantsGen.INTEGER,
        Api.MethodCall(
          Api.MethodPointer(
            "Enso_Test.Test.Main",
            "Enso_Test.Test.Main.T",
            "func1"
          )
        )
      ),
      context.executionComplete(contextId)
    )
  }

  it should "send method pointer updates of partially applied static methods defined as extensions on type" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"

    val metadata = new Metadata("import Standard.Base.Data.Numbers\n\n")
    val id_x1_1  = metadata.addItem(18, 7, "aa")
    val id_x1_2  = metadata.addItem(37, 6, "ab")
    val id_x1    = metadata.addItem(53, 6, "ac")

    val code =
      """main =
        |    x1_1 = T.func1
        |    x1_2 = x1_1 1
        |    x1 = x1_2 2
        |    x1
        |
        |type T
        |    A
        |
        |T.func1 x y = x + y
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(5) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(
        contextId,
        id_x1_1,
        ConstantsGen.FUNCTION_BUILTIN,
        methodCall = Some(
          Api.MethodCall(
            Api.MethodPointer(
              "Enso_Test.Test.Main",
              "Enso_Test.Test.Main.T",
              "func1"
            ),
            Vector(0, 1)
          )
        ),
        payload = Api.ExpressionUpdate.Payload.Value(
          functionSchema = Some(
            Api.FunctionSchema(
              Api.MethodPointer(moduleName, s"$moduleName.T", "func1"),
              Vector(0, 1)
            )
          )
        )
      ),
      TestMessages.update(
        contextId,
        id_x1_2,
        ConstantsGen.FUNCTION_BUILTIN,
        methodCall = Some(
          Api.MethodCall(
            Api.MethodPointer(
              "Enso_Test.Test.Main",
              "Enso_Test.Test.Main.T",
              "func1"
            ),
            Vector(1)
          )
        ),
        payload = Api.ExpressionUpdate.Payload.Value(
          functionSchema = Some(
            Api.FunctionSchema(
              Api.MethodPointer(moduleName, s"$moduleName.T", "func1"),
              Vector(1)
            )
          )
        )
      ),
      TestMessages.update(
        contextId,
        id_x1,
        ConstantsGen.INTEGER,
        Api.MethodCall(
          Api.MethodPointer(
            "Enso_Test.Test.Main",
            "Enso_Test.Test.Main.T",
            "func1"
          )
        )
      ),
      context.executionComplete(contextId)
    )
  }

  it should "send method pointer updates of partially applied methods defined on type" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"

    val metadata = new Metadata("import Standard.Base.Data.Numbers\n\n")
    val id_x1_1  = metadata.addItem(30, 7, "aa")
    val id_x1_2  = metadata.addItem(49, 6, "ab")
    val id_x1    = metadata.addItem(65, 6, "ac")

    val code =
      """main =
        |    a = T.A
        |    x1_1 = a.func1
        |    x1_2 = x1_1 1
        |    x1 = x1_2 2
        |    x1
        |
        |type T
        |    A
        |
        |    func1 self x y = x + y
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(5) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(
        contextId,
        id_x1_1,
        ConstantsGen.FUNCTION_BUILTIN,
        methodCall = Some(
          Api.MethodCall(
            Api.MethodPointer(
              "Enso_Test.Test.Main",
              "Enso_Test.Test.Main.T",
              "func1"
            ),
            Vector(1, 2)
          )
        ),
        payload = Api.ExpressionUpdate.Payload.Value(
          functionSchema = Some(
            Api.FunctionSchema(
              Api.MethodPointer(moduleName, s"$moduleName.T", "func1"),
              Vector(1, 2)
            )
          )
        )
      ),
      TestMessages.update(
        contextId,
        id_x1_2,
        ConstantsGen.FUNCTION_BUILTIN,
        methodCall = Some(
          Api.MethodCall(
            Api.MethodPointer(
              "Enso_Test.Test.Main",
              "Enso_Test.Test.Main.T",
              "func1"
            ),
            Vector(2)
          )
        ),
        payload = Api.ExpressionUpdate.Payload.Value(
          functionSchema = Some(
            Api.FunctionSchema(
              Api.MethodPointer(moduleName, s"$moduleName.T", "func1"),
              Vector(2)
            )
          )
        )
      ),
      TestMessages.update(
        contextId,
        id_x1,
        ConstantsGen.INTEGER,
        Api.MethodCall(
          Api.MethodPointer(
            "Enso_Test.Test.Main",
            "Enso_Test.Test.Main.T",
            "func1"
          )
        )
      ),
      context.executionComplete(contextId)
    )
  }

  it should "send method pointer updates of partially applied methods defined as extensions on type" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"

    val metadata = new Metadata("import Standard.Base.Data.Numbers\n\n")
    val id_x1_1  = metadata.addItem(30, 7, "aa")
    val id_x1_2  = metadata.addItem(49, 6, "ab")
    val id_x1    = metadata.addItem(65, 6, "ac")

    val code =
      """main =
        |    a = T.A
        |    x1_1 = a.func1
        |    x1_2 = x1_1 1
        |    x1 = x1_2 2
        |    x1
        |
        |type T
        |    A
        |
        |T.func1 self x y = x + y
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(5) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(
        contextId,
        id_x1_1,
        ConstantsGen.FUNCTION_BUILTIN,
        methodCall = Some(
          Api.MethodCall(
            Api.MethodPointer(
              "Enso_Test.Test.Main",
              "Enso_Test.Test.Main.T",
              "func1"
            ),
            Vector(1, 2)
          )
        ),
        payload = Api.ExpressionUpdate.Payload.Value(
          functionSchema = Some(
            Api.FunctionSchema(
              Api.MethodPointer(moduleName, s"$moduleName.T", "func1"),
              Vector(1, 2)
            )
          )
        )
      ),
      TestMessages.update(
        contextId,
        id_x1_2,
        ConstantsGen.FUNCTION_BUILTIN,
        methodCall = Some(
          Api.MethodCall(
            Api.MethodPointer(
              "Enso_Test.Test.Main",
              "Enso_Test.Test.Main.T",
              "func1"
            ),
            Vector(2)
          )
        ),
        payload = Api.ExpressionUpdate.Payload.Value(
          functionSchema = Some(
            Api.FunctionSchema(
              Api.MethodPointer(moduleName, s"$moduleName.T", "func1"),
              Vector(2)
            )
          )
        )
      ),
      TestMessages.update(
        contextId,
        id_x1,
        ConstantsGen.INTEGER,
        Api.MethodCall(
          Api.MethodPointer(
            "Enso_Test.Test.Main",
            "Enso_Test.Test.Main.T",
            "func1"
          )
        )
      ),
      context.executionComplete(contextId)
    )
  }

  it should "send method pointer updates of partially applied atom methods called with static notation" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"

    val metadata = new Metadata("import Standard.Base.Data.Numbers\n\n")
    val id_x1_1  = metadata.addItem(30, 7, "aa")
    val id_x1_2  = metadata.addItem(49, 10, "ab")
    val id_x1    = metadata.addItem(69, 6, "ac")

    val code =
      """main =
        |    a = T.A
        |    x1_1 = T.func1
        |    x1_2 = x1_1 a y=2
        |    x1 = x1_2 1
        |    x1
        |
        |type T
        |    A
        |
        |    func1 self x y = x + y
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(5) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(
        contextId,
        id_x1_1,
        ConstantsGen.FUNCTION_BUILTIN,
        methodCall = Some(
          Api.MethodCall(
            Api.MethodPointer(
              "Enso_Test.Test.Main",
              "Enso_Test.Test.Main.T",
              "func1"
            ),
            Vector(0, 1, 2)
          )
        ),
        payload = Api.ExpressionUpdate.Payload.Value(
          functionSchema = Some(
            Api.FunctionSchema(
              Api.MethodPointer(moduleName, s"$moduleName.T", "func1"),
              Vector(0, 1, 2)
            )
          )
        )
      ),
      TestMessages.update(
        contextId,
        id_x1_2,
        ConstantsGen.FUNCTION_BUILTIN,
        methodCall = Some(
          Api.MethodCall(
            Api.MethodPointer(
              "Enso_Test.Test.Main",
              "Enso_Test.Test.Main.T",
              "func1"
            ),
            Vector(1)
          )
        ),
        payload = Api.ExpressionUpdate.Payload.Value(
          functionSchema = Some(
            Api.FunctionSchema(
              Api.MethodPointer(moduleName, s"$moduleName.T", "func1"),
              Vector(1)
            )
          )
        )
      ),
      TestMessages.update(
        contextId,
        id_x1,
        ConstantsGen.INTEGER,
        Api.MethodCall(
          Api.MethodPointer(
            "Enso_Test.Test.Main",
            "Enso_Test.Test.Main.T",
            "func1"
          )
        )
      ),
      context.executionComplete(contextId)
    )
  }

  it should "send method pointer updates of partially applied module methods called without module name" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"

    val metadata = new Metadata("import Standard.Base.Data.Numbers\n\n")
    val id_x1_1  = metadata.addItem(18, 5, "aa")
    val id_x1_2  = metadata.addItem(35, 8, "ab")
    val id_x1    = metadata.addItem(53, 8, "ac")

    val code =
      """main =
        |    x1_1 = func1
        |    x1_2 = x1_1 y=2
        |    x1 = x1_2 1 3
        |    x1
        |
        |func1 x y z = x + y + z
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(5) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(
        contextId,
        id_x1_1,
        ConstantsGen.FUNCTION_BUILTIN,
        methodCall = Some(
          Api.MethodCall(
            Api.MethodPointer(moduleName, moduleName, "func1"),
            Vector(0, 1, 2)
          )
        ),
        payload = Api.ExpressionUpdate.Payload.Value(
          functionSchema = Some(
            Api.FunctionSchema(
              Api.MethodPointer(moduleName, moduleName, "func1"),
              Vector(0, 1, 2)
            )
          )
        )
      ),
      TestMessages.update(
        contextId,
        id_x1_2,
        ConstantsGen.FUNCTION_BUILTIN,
        methodCall = Some(
          Api.MethodCall(
            Api.MethodPointer(moduleName, moduleName, "func1"),
            Vector(0, 2)
          )
        ),
        payload = Api.ExpressionUpdate.Payload.Value(
          functionSchema = Some(
            Api.FunctionSchema(
              Api.MethodPointer(moduleName, moduleName, "func1"),
              Vector(0, 2)
            )
          )
        )
      ),
      TestMessages.update(
        contextId,
        id_x1,
        ConstantsGen.INTEGER,
        Api.MethodCall(Api.MethodPointer(moduleName, moduleName, "func1"))
      ),
      context.executionComplete(contextId)
    )
  }

  it should "send method pointer updates of partially applied module methods called with module name" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"

    val metadata = new Metadata("import Standard.Base.Data.Numbers\n\n")
    val id_x1_1  = metadata.addItem(18, 10, "aa")
    val id_x1_2  = metadata.addItem(40, 8, "ab")
    val id_x1    = metadata.addItem(58, 8, "ac")

    val code =
      """main =
        |    x1_1 = Main.func1
        |    x1_2 = x1_1 y=2
        |    x1 = x1_2 1 3
        |    x1
        |
        |func1 x y z = x + y + z
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(5) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(
        contextId,
        id_x1_1,
        ConstantsGen.FUNCTION_BUILTIN,
        methodCall = Some(
          Api.MethodCall(
            Api.MethodPointer(moduleName, moduleName, "func1"),
            Vector(0, 1, 2)
          )
        ),
        payload = Api.ExpressionUpdate.Payload.Value(
          functionSchema = Some(
            Api.FunctionSchema(
              Api.MethodPointer(moduleName, moduleName, "func1"),
              Vector(0, 1, 2)
            )
          )
        )
      ),
      TestMessages.update(
        contextId,
        id_x1_2,
        ConstantsGen.FUNCTION_BUILTIN,
        methodCall = Some(
          Api.MethodCall(
            Api.MethodPointer(moduleName, moduleName, "func1"),
            Vector(0, 2)
          )
        ),
        payload = Api.ExpressionUpdate.Payload.Value(
          functionSchema = Some(
            Api.FunctionSchema(
              Api.MethodPointer(moduleName, moduleName, "func1"),
              Vector(0, 2)
            )
          )
        )
      ),
      TestMessages.update(
        contextId,
        id_x1,
        ConstantsGen.INTEGER,
        Api.MethodCall(Api.MethodPointer(moduleName, moduleName, "func1"))
      ),
      context.executionComplete(contextId)
    )
  }

  it should "send method pointer updates of a builtin method" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"

    val metadata = new Metadata
    val id_x     = metadata.addItem(46, 17, "aa")

    val code =
      """from Standard.Base import all
        |
        |main =
        |    x = "hello" + "world"
        |    x
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(3) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(
        contextId,
        id_x,
        ConstantsGen.TEXT,
        Api.MethodCall(
          Api.MethodPointer(
            "Standard.Base.Data.Text",
            "Standard.Base.Data.Text.Text",
            "+"
          )
        )
      ),
      context.executionComplete(contextId)
    )
  }

  it should "send method pointer updates of a builtin method called as static" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"

    val metadata = new Metadata
    val id_x     = metadata.addItem(52, 25, "aa")
    val id_y     = metadata.addItem(86, 16, "ab")

    val code =
      """import Standard.Base.Data.Time.Date
        |
        |main =
        |    x = Date.new_builtin 1970 1 1
        |    y = Date.Date.year x
        |    y
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(4) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(contextId, id_x, ConstantsGen.DATE),
      TestMessages.update(
        contextId,
        id_y,
        ConstantsGen.INTEGER_BUILTIN,
        Api.MethodCall(
          Api.MethodPointer(
            "Standard.Base.Data.Time.Date",
            "Standard.Base.Data.Time.Date.Date",
            "year"
          )
        )
      ),
      context.executionComplete(contextId)
    )
  }

  it should "not send method pointer updates of a builtin method defined as static" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"

    val metadata = new Metadata
    val id_x     = metadata.addItem(52, 25, "aa")

    val code =
      """import Standard.Base.Data.Time.Date
        |
        |main =
        |    x = Date.new_builtin 2022 1 1
        |    x
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(3) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(contextId, id_x, "Standard.Base.Data.Time.Date.Date"),
      context.executionComplete(contextId)
    )
  }

  it should "send updates from last line" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"

    val metadata  = new Metadata("import Standard.Base.Data.Numbers\n\n")
    val idMain    = metadata.addItem(23, 12)
    val idMainFoo = metadata.addItem(28, 7)

    val code =
      """foo a b = a + b
        |
        |main =
        |    foo 1 2
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(4) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(
        contextId,
        idMainFoo,
        ConstantsGen.INTEGER,
        Api.MethodCall(Api.MethodPointer(moduleName, moduleName, "foo"))
      ),
      TestMessages.update(contextId, idMain, ConstantsGen.INTEGER),
      context.executionComplete(contextId)
    )
  }

  it should "compute side effects correctly from last line" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"

    val metadata  = new Metadata
    val idMain    = metadata.addItem(54, 25)
    val idMainFoo = metadata.addItem(71, 7)

    val code =
      """from Standard.Base import all
        |
        |foo a b = a + b
        |
        |main =
        |    IO.println (foo 1 2)
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, "Enso_Test.Test.Main", "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(4) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(
        contextId,
        idMainFoo,
        ConstantsGen.INTEGER,
        Api.MethodCall(Api.MethodPointer(moduleName, moduleName, "foo"))
      ),
      TestMessages.update(contextId, idMain, ConstantsGen.NOTHING),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("3")
  }

  it should "run State getting the initial state" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"

    val metadata  = new Metadata
    val idMain    = metadata.addItem(73, 36)
    val idMainBar = metadata.addItem(105, 3)

    val code =
      """from Standard.Base import all
        |import Standard.Base.Runtime.State
        |
        |main = IO.println (State.run Number 42 bar)
        |
        |bar = State.get Number
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, "Enso_Test.Test.Main", "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(4) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(
        contextId,
        idMainBar,
        ConstantsGen.INTEGER,
        Api.MethodCall(Api.MethodPointer(moduleName, moduleName, "bar"))
      ),
      TestMessages.update(
        contextId,
        idMain,
        ConstantsGen.NOTHING,
        methodCall = Some(
          Api.MethodCall(
            Api.MethodPointer(
              "Standard.Base.IO",
              "Standard.Base.IO",
              "println"
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("42")
  }

  it should "run State setting the state" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"

    val metadata  = new Metadata
    val idMain    = metadata.addItem(73, 35)
    val idMainBar = metadata.addItem(104, 3)

    val code =
      """from Standard.Base import all
        |import Standard.Base.Runtime.State
        |
        |main = IO.println (State.run Number 0 bar)
        |
        |bar =
        |    State.put Number 10
        |    State.get Number
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(4) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(
        contextId,
        idMainBar,
        ConstantsGen.INTEGER,
        Api.MethodCall(Api.MethodPointer(moduleName, moduleName, "bar"))
      ),
      TestMessages.update(
        contextId,
        idMain,
        ConstantsGen.NOTHING,
        methodCall = Some(
          Api.MethodCall(
            Api.MethodPointer(
              "Standard.Base.IO",
              "Standard.Base.IO",
              "println"
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("10")
  }

  it should "send updates of a function call" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"

    val metadata  = new Metadata("import Standard.Base.Data.Numbers\n\n")
    val idMain    = metadata.addItem(23, 18)
    val idMainFoo = metadata.addItem(28, 7)

    val code =
      """foo a b = a + b
        |
        |main =
        |    foo 1 2
        |    1
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(4) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(
        contextId,
        idMainFoo,
        ConstantsGen.INTEGER,
        Api.MethodCall(Api.MethodPointer(moduleName, moduleName, "foo"))
      ),
      TestMessages.update(contextId, idMain, ConstantsGen.INTEGER),
      context.executionComplete(contextId)
    )
  }

  it should "send updates when function body is changed" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"
    val metadata   = new Metadata

    // foo definition
    metadata.addItem(25, 22)
    // foo name
    metadata.addItem(25, 3)
    val fooX    = metadata.addItem(45, 1, "aa")
    val fooRes  = metadata.addItem(51, 1, "ab")
    val mainFoo = metadata.addItem(69, 3, "ac")
    val mainRes = metadata.addItem(77, 12, "ad")

    val code =
      """from Standard.Base import all
        |
        |foo =
        |    x = 4
        |    x
        |
        |main =
        |    y = foo
        |    IO.println y
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // Set sources for the module
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(4) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages
        .update(
          contextId,
          mainFoo,
          ConstantsGen.INTEGER,
          Api.MethodCall(Api.MethodPointer(moduleName, moduleName, "foo"))
        ),
      TestMessages.update(
        contextId,
        mainRes,
        ConstantsGen.NOTHING,
        methodCall = Some(
          Api.MethodCall(
            Api.MethodPointer(
              "Standard.Base.IO",
              "Standard.Base.IO",
              "println"
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("4")

    // push foo call
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(contextId, Api.StackItem.LocalCall(mainFoo))
      )
    )
    context.receiveNIgnoreStdLib(4) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(contextId, fooX, ConstantsGen.INTEGER),
      TestMessages.update(contextId, fooRes, ConstantsGen.INTEGER),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("4")

    // Modify the foo method
    context.send(
      Api.Request(
        Api.EditFileNotification(
          mainFile,
          Seq(
            TextEdit(
              model.Range(model.Position(3, 8), model.Position(3, 9)),
              "5"
            )
          ),
          execute = true,
          idMap   = None
        )
      )
    )
    context.receiveN(4) should contain theSameElementsAs Seq(
      TestMessages.pending(contextId, fooX, fooRes, mainFoo, mainRes),
      TestMessages
        .update(contextId, fooX, ConstantsGen.INTEGER, typeChanged = false),
      TestMessages
        .update(contextId, fooRes, ConstantsGen.INTEGER, typeChanged = false),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("5")

    // pop the foo call
    context.send(Api.Request(requestId, Api.PopContextRequest(contextId)))
    context.receiveN(4) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PopContextResponse(contextId)),
      TestMessages
        .update(
          contextId,
          mainRes,
          ConstantsGen.NOTHING,
          methodCall = Some(
            Api.MethodCall(
              Api.MethodPointer(
                "Standard.Base.IO",
                "Standard.Base.IO",
                "println"
              )
            )
          ),
          typeChanged = false
        ),
      TestMessages.update(
        contextId,
        mainFoo,
        ConstantsGen.INTEGER,
        Api.MethodCall(Api.MethodPointer(moduleName, moduleName, "foo")),
        fromCache   = false,
        typeChanged = false
      ),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("5")
  }

  it should "obey the execute parameter of edit command" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"
    val metadata   = new Metadata

    // foo definition
    metadata.addItem(31, 22)
    // foo name
    metadata.addItem(31, 3)
    val mainFoo = metadata.addItem(69, 3)
    val mainRes = metadata.addItem(77, 12)

    val code =
      """from Standard.Base import all
        |
        |foo =
        |    x = 4
        |    x
        |
        |main =
        |    y = foo
        |    IO.println y
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // Set sources for the module
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(4) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(
        contextId,
        mainFoo,
        ConstantsGen.INTEGER,
        Api.MethodCall(Api.MethodPointer(moduleName, moduleName, "foo"))
      ),
      TestMessages.update(
        contextId,
        mainRes,
        ConstantsGen.NOTHING,
        methodCall = Some(
          Api.MethodCall(
            Api.MethodPointer(
              "Standard.Base.IO",
              "Standard.Base.IO",
              "println"
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("4")

    // Modify the foo method
    context.send(
      Api.Request(
        Api.EditFileNotification(
          mainFile,
          Seq(
            TextEdit(
              model.Range(model.Position(3, 8), model.Position(3, 9)),
              "5"
            )
          ),
          execute = false,
          idMap   = None
        )
      )
    )
    context.receiveNone shouldEqual None

    // Modify the foo method
    context.send(
      Api.Request(
        Api.EditFileNotification(
          mainFile,
          Seq(
            TextEdit(
              model.Range(model.Position(3, 8), model.Position(3, 9)),
              "6"
            )
          ),
          execute = true,
          idMap   = None
        )
      )
    )
    context.receiveN(4) should contain theSameElementsAs Seq(
      TestMessages.pending(contextId, mainFoo, mainRes),
      TestMessages.update(
        contextId,
        mainFoo,
        ConstantsGen.INTEGER,
        Api.MethodCall(Api.MethodPointer(moduleName, moduleName, "foo")),
        fromCache   = false,
        typeChanged = false
      ),
      TestMessages.update(
        contextId,
        mainRes,
        ConstantsGen.NOTHING,
        methodCall = Some(
          Api.MethodCall(
            Api.MethodPointer(
              "Standard.Base.IO",
              "Standard.Base.IO",
              "println"
            )
          )
        ),
        typeChanged = false
      ),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("6")
  }

  it should "send updates when the type is not changed" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"
    val idMain     = context.Main.metadata.addItem(54, 46, "aaaaa")
    val contents   = context.Main.code
    val mainFile   = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(6) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.Main.Update.mainX(contextId),
      context.Main.Update.mainY(contextId),
      context.Main.Update.mainZ(contextId),
      TestMessages.update(contextId, idMain, ConstantsGen.INTEGER),
      context.executionComplete(contextId)
    )

    // push foo call
    val item2 = Api.StackItem.LocalCall(context.Main.idMainY)
    context.send(
      Api.Request(requestId, Api.PushContextRequest(contextId, item2))
    )
    context.receiveN(4) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.Main.Update.fooY(contextId),
      context.Main.Update.fooZ(contextId),
      context.executionComplete(contextId)
    )

    // pop foo call
    context.send(Api.Request(requestId, Api.PopContextRequest(contextId)))
    context.receiveNIgnoreStdLib(5) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PopContextResponse(contextId)),
      context.Main.Update.mainY(contextId, fromCache = true),
      context.Main.Update.mainZ(contextId, fromCache = true),
      TestMessages
        .update(contextId, idMain, ConstantsGen.INTEGER, typeChanged = false),
      context.executionComplete(contextId)
    )

    // pop main
    context.send(Api.Request(requestId, Api.PopContextRequest(contextId)))
    context.receiveN(1) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PopContextResponse(contextId))
    )
  }

  it should "send updates when the type is changed" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"

    val metadata  = new Metadata
    val idResult  = metadata.addItem(51, 4, "aae")
    val idPrintln = metadata.addItem(60, 17, "aaf")
    val idMain    = metadata.addItem(37, 40, "aaa")
    val code =
      """from Standard.Base import all
        |
        |main =
        |    result = 1337
        |    IO.println result
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(5) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(contextId, idResult, ConstantsGen.INTEGER),
      TestMessages.update(
        contextId,
        idPrintln,
        ConstantsGen.NOTHING,
        methodCall = Some(
          Api.MethodCall(
            Api.MethodPointer(
              "Standard.Base.IO",
              "Standard.Base.IO",
              "println"
            )
          )
        )
      ),
      TestMessages.update(contextId, idMain, ConstantsGen.NOTHING),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("1337")

    // Modify the file
    context.send(
      Api.Request(
        Api.EditFileNotification(
          mainFile,
          Seq(
            TextEdit(
              model.Range(model.Position(3, 13), model.Position(3, 17)),
              "\"Hi\""
            )
          ),
          execute = true,
          idMap   = None
        )
      )
    )
    context.receiveN(5) should contain theSameElementsAs Seq(
      TestMessages.pending(contextId, idResult, idPrintln, idMain),
      TestMessages.update(contextId, idResult, ConstantsGen.TEXT),
      TestMessages.update(
        contextId,
        idPrintln,
        ConstantsGen.NOTHING,
        methodCall = Some(
          Api.MethodCall(
            Api.MethodPointer(
              "Standard.Base.IO",
              "Standard.Base.IO",
              "println"
            )
          )
        ),
        typeChanged = false
      ),
      TestMessages
        .update(contextId, idMain, ConstantsGen.NOTHING, typeChanged = false),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("Hi")
  }

  it should "send updates when the method pointer is changed" in {
    val contextId      = UUID.randomUUID()
    val requestId      = UUID.randomUUID()
    val moduleName     = "Enso_Test.Test.Main"
    val numberTypeName = "Standard.Base.Data.Numbers.Number"

    val metadata = new Metadata
    val idMain   = metadata.addItem(37, 34, "aaaa")
    val idMainA  = metadata.addItem(46, 8, "aabb")
    val idMainP  = metadata.addItem(59, 12, "aacc")
    // pie id
    metadata.addItem(89, 1, "eee")
    // uwu id
    metadata.addItem(87, 1, "bbb")
    // hie id
    metadata.addItem(95, 6, "fff")
    // Number.x id
    metadata.addItem(115, 1, "999")
    val code =
      """from Standard.Base import all
        |
        |main =
        |    a = 123 + 21
        |    IO.println a
        |
        |pie = 3
        |uwu = 7
        |hie = "hie!"
        |Number.x self y = y
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(5) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(
        contextId,
        idMainA,
        ConstantsGen.INTEGER,
        Api.MethodCall(
          Api.MethodPointer(
            "Standard.Base.Data.Numbers",
            ConstantsGen.INTEGER,
            "+"
          )
        )
      ),
      TestMessages.update(
        contextId,
        idMainP,
        ConstantsGen.NOTHING,
        methodCall = Some(
          Api.MethodCall(
            Api.MethodPointer(
              "Standard.Base.IO",
              "Standard.Base.IO",
              "println"
            )
          )
        )
      ),
      TestMessages.update(contextId, idMain, ConstantsGen.NOTHING),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("144")

    // Edit s/123 + 21/1234.x 4/
    context.send(
      Api.Request(
        Api.EditFileNotification(
          mainFile,
          Seq(
            TextEdit(
              model.Range(model.Position(3, 8), model.Position(3, 16)),
              "1234.x 4"
            )
          ),
          execute = true,
          idMap   = None
        )
      )
    )
    context.receiveN(5) should contain theSameElementsAs Seq(
      TestMessages.pending(contextId, idMain, idMainA, idMainP),
      TestMessages.update(
        contextId,
        idMainA,
        ConstantsGen.INTEGER,
        Api.MethodCall(Api.MethodPointer(moduleName, ConstantsGen.NUMBER, "x"))
      ),
      TestMessages.update(
        contextId,
        idMainP,
        ConstantsGen.NOTHING,
        methodCall = Some(
          Api.MethodCall(
            Api.MethodPointer(
              "Standard.Base.IO",
              "Standard.Base.IO",
              "println"
            )
          )
        ),
        typeChanged = false
      ),
      TestMessages
        .update(contextId, idMain, ConstantsGen.NOTHING, typeChanged = false),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("4")

    // Edit s/1234.x 4/1000.x 5/
    context.send(
      Api.Request(
        Api.EditFileNotification(
          mainFile,
          Seq(
            TextEdit(
              model.Range(model.Position(3, 8), model.Position(3, 16)),
              "1000.x 5"
            )
          ),
          execute = true,
          idMap   = None
        )
      )
    )
    context.receiveN(5) shouldEqual Seq(
      TestMessages.pending(contextId, idMain, idMainA, idMainP),
      TestMessages.update(
        contextId,
        idMainA,
        ConstantsGen.INTEGER,
        Api.MethodCall(Api.MethodPointer(moduleName, numberTypeName, "x")),
        fromCache   = false,
        typeChanged = false
      ),
      TestMessages.update(
        contextId,
        idMainP,
        ConstantsGen.NOTHING,
        methodCall = Some(
          Api.MethodCall(
            Api.MethodPointer(
              "Standard.Base.IO",
              "Standard.Base.IO",
              "println"
            )
          )
        ),
        typeChanged = false
      ),
      TestMessages
        .update(contextId, idMain, ConstantsGen.NOTHING, typeChanged = false),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("5")

    // Edit s/1000.x 5/Main.pie/
    context.send(
      Api.Request(
        Api.EditFileNotification(
          mainFile,
          Seq(
            TextEdit(
              model.Range(model.Position(3, 8), model.Position(3, 16)),
              "Main.pie"
            )
          ),
          execute = true,
          idMap   = None
        )
      )
    )
    context.receiveN(5) should contain theSameElementsAs Seq(
      TestMessages.pending(contextId, idMain, idMainA, idMainP),
      TestMessages.update(
        contextId,
        idMainA,
        ConstantsGen.INTEGER,
        Api.MethodCall(Api.MethodPointer(moduleName, moduleName, "pie")),
        fromCache   = false,
        typeChanged = true
      ),
      TestMessages.update(
        contextId,
        idMainP,
        ConstantsGen.NOTHING,
        methodCall = Some(
          Api.MethodCall(
            Api.MethodPointer(
              "Standard.Base.IO",
              "Standard.Base.IO",
              "println"
            )
          )
        ),
        typeChanged = false
      ),
      TestMessages
        .update(contextId, idMain, ConstantsGen.NOTHING, typeChanged = false),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("3")

    // Edit s/Main.pie/Main.uwu/
    context.send(
      Api.Request(
        Api.EditFileNotification(
          mainFile,
          Seq(
            TextEdit(
              model.Range(model.Position(3, 8), model.Position(3, 16)),
              "Main.uwu"
            )
          ),
          execute = true,
          idMap   = None
        )
      )
    )
    context.receiveN(5) should contain theSameElementsAs Seq(
      TestMessages.pending(contextId, idMain, idMainA, idMainP),
      TestMessages.update(
        contextId,
        idMainA,
        ConstantsGen.INTEGER,
        Api.MethodCall(Api.MethodPointer(moduleName, moduleName, "uwu")),
        fromCache   = false,
        typeChanged = true
      ),
      TestMessages.update(
        contextId,
        idMainP,
        ConstantsGen.NOTHING,
        methodCall = Some(
          Api.MethodCall(
            Api.MethodPointer(
              "Standard.Base.IO",
              "Standard.Base.IO",
              "println"
            )
          )
        ),
        typeChanged = false
      ),
      TestMessages
        .update(contextId, idMain, ConstantsGen.NOTHING, typeChanged = false),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("7")

    // Edit s/Main.uwu/Main.hie/
    context.send(
      Api.Request(
        Api.EditFileNotification(
          mainFile,
          Seq(
            TextEdit(
              model.Range(model.Position(3, 8), model.Position(3, 16)),
              "Main.hie"
            )
          ),
          execute = true,
          idMap   = None
        )
      )
    )
    context.receiveN(5) should contain theSameElementsAs Seq(
      TestMessages.pending(contextId, idMain, idMainA, idMainP),
      TestMessages.update(
        contextId,
        idMainA,
        ConstantsGen.TEXT,
        Api.MethodCall(Api.MethodPointer(moduleName, moduleName, "hie"))
      ),
      TestMessages.update(
        contextId,
        idMainP,
        ConstantsGen.NOTHING,
        methodCall = Some(
          Api.MethodCall(
            Api.MethodPointer(
              "Standard.Base.IO",
              "Standard.Base.IO",
              "println"
            )
          )
        ),
        typeChanged = false
      ),
      TestMessages
        .update(contextId, idMain, ConstantsGen.NOTHING, typeChanged = false),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("hie!")

    // Edit s/Main.hie/"Hello!"/
    context.send(
      Api.Request(
        Api.EditFileNotification(
          mainFile,
          Seq(
            TextEdit(
              model.Range(model.Position(3, 8), model.Position(3, 16)),
              "\"Hello!\""
            )
          ),
          execute = true,
          idMap   = None
        )
      )
    )
    context.receiveN(5) should contain theSameElementsAs Seq(
      TestMessages.pending(contextId, idMain, idMainA, idMainP),
      TestMessages.update(
        contextId,
        idMainA,
        ConstantsGen.TEXT,
        fromCache   = false,
        typeChanged = true
      ),
      TestMessages.update(
        contextId,
        idMainP,
        ConstantsGen.NOTHING,
        methodCall = Some(
          Api.MethodCall(
            Api.MethodPointer(
              "Standard.Base.IO",
              "Standard.Base.IO",
              "println"
            )
          )
        ),
        typeChanged = false
      ),
      TestMessages
        .update(contextId, idMain, ConstantsGen.NOTHING, typeChanged = false),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("Hello!")
  }

  it should "send updates for overloaded functions" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"

    val metadata = new Metadata
    val idMain   = metadata.addItem(122, 87, "aaaa")
    val id1      = metadata.addItem(131, 15, "aad1")
    val id2      = metadata.addItem(151, 18, "aad2")
    val id3      = metadata.addItem(174, 15, "aad3")
    // Note that Nothing.Nothing is on purpose.
    // If not provided the full name it will resolve the expression Nothing to a Nothing module.
    // Similarly Text.Text. That in turn will mismatch the expectations for method types which actually
    // return proper types.
    val code =
      """from Standard.Base.Data.Numbers import Number
        |from Standard.Base.Data.Text import all
        |import Standard.Base.Nothing
        |
        |main =
        |    x = 15.overloaded 1
        |    "foo".overloaded 2
        |    10.overloaded x
        |    Nothing.Nothing
        |
        |Text.overloaded self arg = arg + 1
        |Number.overloaded self arg = arg + 2
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(6) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(contextId, idMain, ConstantsGen.NOTHING),
      TestMessages.update(
        contextId,
        id1,
        ConstantsGen.INTEGER,
        Api.MethodCall(
          Api.MethodPointer(moduleName, ConstantsGen.NUMBER, "overloaded")
        )
      ),
      TestMessages.update(
        contextId,
        id2,
        ConstantsGen.INTEGER,
        Api.MethodCall(
          Api.MethodPointer(moduleName, ConstantsGen.TEXT, "overloaded")
        )
      ),
      TestMessages.update(
        contextId,
        id3,
        ConstantsGen.INTEGER,
        Api.MethodCall(
          Api.MethodPointer(moduleName, ConstantsGen.NUMBER, "overloaded")
        )
      ),
      context.executionComplete(contextId)
    )

    // push call1
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.LocalCall(id1)
        )
      )
    )
    context.receiveN(2) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.executionComplete(contextId)
    )

    // pop call1
    context.send(Api.Request(requestId, Api.PopContextRequest(contextId)))
    context.receiveN(6) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PopContextResponse(contextId)),
      TestMessages.update(
        contextId,
        id1,
        ConstantsGen.INTEGER,
        Api.MethodCall(
          Api.MethodPointer(moduleName, ConstantsGen.NUMBER, "overloaded")
        ),
        fromCache   = true,
        typeChanged = true
      ),
      TestMessages.update(
        contextId,
        id2,
        ConstantsGen.INTEGER,
        Api.MethodCall(
          Api.MethodPointer(moduleName, ConstantsGen.TEXT, "overloaded")
        ),
        fromCache   = false,
        typeChanged = false
      ),
      TestMessages.update(
        contextId,
        id3,
        ConstantsGen.INTEGER,
        Api.MethodCall(
          Api.MethodPointer(moduleName, ConstantsGen.NUMBER, "overloaded")
        ),
        fromCache   = false,
        typeChanged = false
      ),
      TestMessages.update(
        contextId,
        idMain,
        ConstantsGen.NOTHING,
        typeChanged = false
      ),
      context.executionComplete(contextId)
    )

    // push call2
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.LocalCall(id2)
        )
      )
    )
    context.receiveN(2) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.executionComplete(contextId)
    )

    // pop call2
    context.send(Api.Request(requestId, Api.PopContextRequest(contextId)))
    context.receiveN(6) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PopContextResponse(contextId)),
      TestMessages.update(
        contextId,
        id2,
        ConstantsGen.INTEGER,
        Api.MethodCall(
          Api.MethodPointer(moduleName, ConstantsGen.TEXT, "overloaded")
        ),
        fromCache   = false,
        typeChanged = false
      ),
      TestMessages.update(
        contextId,
        id3,
        ConstantsGen.INTEGER,
        Api.MethodCall(
          Api.MethodPointer(moduleName, ConstantsGen.NUMBER, "overloaded")
        ),
        fromCache   = false,
        typeChanged = false
      ),
      TestMessages.update(
        contextId,
        idMain,
        ConstantsGen.NOTHING,
        typeChanged = false
      ),
      TestMessages.update(
        contextId,
        id1,
        ConstantsGen.INTEGER,
        Api.MethodCall(
          Api.MethodPointer(moduleName, ConstantsGen.NUMBER, "overloaded")
        ),
        fromCache   = true,
        typeChanged = true
      ),
      context.executionComplete(contextId)
    )

    // push call3
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.LocalCall(id3)
        )
      )
    )
    context.receiveN(2) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.executionComplete(contextId)
    )

    // pop call3
    context.send(Api.Request(requestId, Api.PopContextRequest(contextId)))
    context.receiveN(6) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PopContextResponse(contextId)),
      TestMessages.update(
        contextId,
        id2,
        ConstantsGen.INTEGER,
        Api.MethodCall(
          Api.MethodPointer(moduleName, ConstantsGen.TEXT, "overloaded")
        ),
        fromCache   = false,
        typeChanged = false
      ),
      TestMessages.update(
        contextId,
        id3,
        ConstantsGen.INTEGER,
        Api.MethodCall(
          Api.MethodPointer(moduleName, ConstantsGen.NUMBER, "overloaded")
        ),
        fromCache   = false,
        typeChanged = false
      ),
      TestMessages.update(
        contextId,
        idMain,
        ConstantsGen.NOTHING,
        fromCache   = false,
        typeChanged = false
      ),
      TestMessages.update(
        contextId,
        id1,
        ConstantsGen.INTEGER,
        Api.MethodCall(
          Api.MethodPointer(moduleName, ConstantsGen.NUMBER, "overloaded")
        ),
        fromCache   = true,
        typeChanged = true
      ),
      context.executionComplete(contextId)
    )
  }

  it should "send updates for a lambda" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"
    val metadata   = new Metadata

    val xId     = metadata.addItem(46, 10)
    val mainRes = metadata.addItem(61, 12)

    val code =
      """from Standard.Base import all
        |
        |main =
        |    x = a -> a + 1
        |    IO.println x
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // Set sources for the module
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(4) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(contextId, xId, ConstantsGen.FUNCTION),
      TestMessages.update(
        contextId,
        mainRes,
        ConstantsGen.NOTHING,
        methodCall = Some(
          Api.MethodCall(
            Api.MethodPointer(
              "Standard.Base.IO",
              "Standard.Base.IO",
              "println"
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )
  }

  it should "send updates for a constructor type" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"
    val metadata   = new Metadata

    val idMain = metadata.addItem(39, 27)

    val code =
      """type My_Type
        |    My_Constructor
        |
        |main =
        |    My_Type.My_Constructor
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // Set sources for the module
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveN(3) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(contextId, idMain, s"$moduleName.My_Type"),
      context.executionComplete(contextId)
    )
  }

  it should "support file modification operations without attached ids" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"

    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    val code =
      """import Standard.Base.IO
        |
        |main = IO.println "I'm a file!"
        |""".stripMargin.linesIterator.mkString("\n")

    // Create a new file
    val mainFile = context.writeMain(code)

    // Set sources for the module
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, code))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )
    context.consumeOut shouldEqual List()

    // Push new item on the stack to trigger the re-execution
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem
            .ExplicitCall(
              Api.MethodPointer(moduleName, moduleName, "main"),
              None,
              Vector()
            )
        )
      )
    )
    context.receiveNIgnoreStdLib(2) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("I'm a file!")

    // Modify the file
    context.send(
      Api.Request(
        Api.EditFileNotification(
          mainFile,
          Seq(
            TextEdit(
              model.Range(model.Position(2, 25), model.Position(2, 29)),
              "modified"
            )
          ),
          execute = true,
          idMap   = None
        )
      )
    )
    context.receiveN(1) shouldEqual Seq(
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("I'm a modified!")

    // Close the file
    context.send(Api.Request(Api.CloseFileNotification(mainFile)))
    context.consumeOut shouldEqual List()
  }

  it should "support file modifications after reopening the file" in {
    val contextId = UUID.randomUUID()
    val requestId = UUID.randomUUID()

    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    val moduleName = "Enso_Test.Test.Main"
    val code =
      """import Standard.Base.IO
        |
        |main = IO.println "I'm a file!"
        |""".stripMargin.linesIterator.mkString("\n")

    // Create a new file
    val mainFile = context.writeMain(code)

    // Set sources for the module
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, code))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )
    context.consumeOut shouldEqual List()

    // Push new item on the stack to trigger the re-execution
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem
            .ExplicitCall(
              Api.MethodPointer(moduleName, moduleName, "main"),
              None,
              Vector()
            )
        )
      )
    )
    context.receiveNIgnoreStdLib(2) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("I'm a file!")

    // Close the file
    context.send(Api.Request(Api.CloseFileNotification(mainFile)))
    context.consumeOut shouldEqual List()

    val contextId2 = UUID.randomUUID()
    val requestId2 = UUID.randomUUID()

    context.send(Api.Request(requestId2, Api.CreateContextRequest(contextId2)))
    context.receive shouldEqual Some(
      Api.Response(requestId2, Api.CreateContextResponse(contextId2))
    )

    // Re-open the the file and apply the same operation
    context.send(
      Api.Request(requestId2, Api.OpenFileRequest(mainFile, code))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId2), Api.OpenFileResponse)
    )
    context.consumeOut shouldEqual List()

    // Modify the file
    context.send(
      Api.Request(
        Api.EditFileNotification(
          mainFile,
          Seq(
            TextEdit(
              model.Range(model.Position(2, 25), model.Position(2, 29)),
              "modified"
            )
          ),
          execute = true,
          idMap   = None
        )
      )
    )
    context.receiveN(1) shouldEqual Seq(
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("I'm a modified!")

  }

  it should "support file modification operations with attached ids" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"
    val metadata   = new Metadata
    val idMain     = metadata.addItem(7, 2)
    val code       = metadata.appendToCode("main = 84")

    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // Create a new file
    val mainFile = context.writeMain(code)

    // Set sources for the module
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, code))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // Push new item on the stack to trigger the re-execution
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem
            .ExplicitCall(
              Api.MethodPointer(moduleName, moduleName, "main"),
              None,
              Vector()
            )
        )
      )
    )
    context.receiveN(3) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(contextId, idMain, ConstantsGen.INTEGER_BUILTIN),
      context.executionComplete(contextId)
    )

    // Modify the file
    context.send(
      Api.Request(
        Api.EditFileNotification(
          mainFile,
          Seq(
            TextEdit(
              model.Range(model.Position(0, 0), model.Position(0, 9)),
              "main = 42"
            )
          ),
          execute = true,
          idMap   = None
        )
      )
    )
    context.receiveN(3) shouldEqual Seq(
      TestMessages.pending(contextId, idMain),
      TestMessages.update(
        contextId,
        idMain,
        ConstantsGen.INTEGER_BUILTIN,
        typeChanged = false
      ),
      context.executionComplete(contextId)
    )
  }

  it should "send suggestion notifications when file is executed" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"
    val idMain     = context.Main.metadata.addItem(54, 46, "aaaa")

    val mainFile = context.writeMain(context.Main.code)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // Set sources for the module
    context.send(
      Api.Request(
        requestId,
        Api.OpenFileRequest(mainFile, context.Main.code)
      )
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    val item1 = Api.StackItem.ExplicitCall(
      Api.MethodPointer(moduleName, moduleName, "main"),
      None,
      Vector()
    )
    context.send(
      Api.Request(requestId, Api.PushContextRequest(contextId, item1))
    )
    context.receiveNIgnoreStdLib(6) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.Main.Update.mainX(contextId),
      context.Main.Update.mainY(contextId),
      context.Main.Update.mainZ(contextId),
      TestMessages.update(contextId, idMain, ConstantsGen.INTEGER),
      context.executionComplete(contextId)
    )

    // push foo call
    val item2 = Api.StackItem.LocalCall(context.Main.idMainY)
    context.send(
      Api.Request(requestId, Api.PushContextRequest(contextId, item2))
    )
    context.receiveN(4) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.Main.Update.fooY(contextId),
      context.Main.Update.fooZ(contextId),
      context.executionComplete(contextId)
    )

    // pop foo call
    context.send(Api.Request(requestId, Api.PopContextRequest(contextId)))
    context.receiveN(5) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PopContextResponse(contextId)),
      context.Main.Update.mainY(contextId, fromCache = true),
      context.Main.Update.mainZ(contextId, fromCache = true),
      TestMessages
        .update(contextId, idMain, ConstantsGen.INTEGER, typeChanged = false),
      context.executionComplete(contextId)
    )

    // pop main
    context.send(Api.Request(requestId, Api.PopContextRequest(contextId)))
    context.receiveN(1) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PopContextResponse(contextId))
    )

    // pop empty stack
    context.send(Api.Request(requestId, Api.PopContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.EmptyStackError(contextId))
    )
  }

  it should "send suggestion notifications when file is modified" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"
    val newline    = System.lineSeparator()

    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    val code =
      """from Standard.Base.Data.Numbers import Number
        |import Standard.Base.IO
        |
        |main = IO.println "I'm a file!"
        |""".stripMargin.linesIterator.mkString("\n")

    // Create a new file
    val mainFile = context.writeMain(code)

    // Set sources for the module
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, code))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )
    context.consumeOut shouldEqual List()

    // Push new item on the stack to trigger the re-execution
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem
            .ExplicitCall(
              Api.MethodPointer(moduleName, moduleName, "main"),
              None,
              Vector()
            )
        )
      )
    )
    context.receiveNIgnoreStdLib(2) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("I'm a file!")

    /*
      Modify the file:
      """from Standard.Base.Data.Numbers import Number
        |import Standard.Base.IO
        |
        |Number.lucky = 42
        |
        |main = IO.println "I'm a modified!"
        |""".stripMargin.linesIterator.mkString("\n")
     */
    context.send(
      Api.Request(
        Api.EditFileNotification(
          mainFile,
          Seq(
            TextEdit(
              model.Range(model.Position(3, 25), model.Position(3, 29)),
              "modified"
            ),
            TextEdit(
              model.Range(model.Position(3, 0), model.Position(3, 0)),
              s"Number.lucky = 42$newline$newline"
            )
          ),
          execute = true,
          idMap   = None
        )
      )
    )
    context.receiveN(1) should contain theSameElementsAs Seq(
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("I'm a modified!")

    // Close the file
    context.send(Api.Request(Api.CloseFileNotification(mainFile)))
    context.receiveNone shouldEqual None
    context.consumeOut shouldEqual List()
  }

  it should "send expression updates when file is restored" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"

    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    val metadata = new Metadata
    val idText   = metadata.addItem(49, 12, "aa")
    val idRes    = metadata.addItem(66, 15, "ab")

    def template(text: String) =
      metadata.appendToCode(
        s"""from Standard.Base import all
           |
           |main =
           |    text = "$text"
           |    IO.println text
           |""".stripMargin.linesIterator.mkString("\n")
      )

    val prompt1 = "I'm a one!"
    val code    = template(prompt1)

    // Create a new file
    val mainFile = context.writeMain(code)

    // Set sources for the module
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, code))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )
    context.consumeOut shouldEqual List()

    // Push new item on the stack to trigger the re-execution
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem
            .ExplicitCall(
              Api.MethodPointer(moduleName, moduleName, "main"),
              None,
              Vector()
            )
        )
      )
    )
    context.receiveNIgnoreStdLib(4) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(contextId, idText, ConstantsGen.TEXT),
      TestMessages.update(
        contextId,
        idRes,
        ConstantsGen.NOTHING,
        methodCall = Some(
          Api.MethodCall(
            Api.MethodPointer(
              "Standard.Base.IO",
              "Standard.Base.IO",
              "println"
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List(prompt1)

    // Simulate file update in FS
    val prompt2 = "I'm a two!"
    val code2   = template(prompt2)
    context.writeMain(code2)

    context.send(
      Api.Request(
        Api.EditFileNotification(
          mainFile,
          Seq(
            TextEdit(
              model.Range(model.Position(0, 0), model.Position(9, 2)),
              code2
            )
          ),
          execute = true,
          idMap   = None
        )
      )
    )
    context.receiveNIgnorePendingExpressionUpdates(
      3
    ) should contain theSameElementsAs Seq(
      TestMessages
        .update(contextId, idText, ConstantsGen.TEXT, typeChanged = false),
      TestMessages.update(
        contextId,
        idRes,
        ConstantsGen.NOTHING,
        methodCall = Some(
          Api.MethodCall(
            Api.MethodPointer(
              "Standard.Base.IO",
              "Standard.Base.IO",
              "println"
            )
          )
        ),
        typeChanged = false
      ),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List(prompt2)

    // Close the file
    context.send(Api.Request(Api.CloseFileNotification(mainFile)))
    context.receiveNone shouldEqual None
    context.consumeOut shouldEqual List()
  }

  it should "return error when module not found" in {
    val contents  = context.Main.code
    val mainFile  = context.writeMain(context.Main.code)
    val contextId = UUID.randomUUID()
    val requestId = UUID.randomUUID()

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // Set sources for the module
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )
    context.receiveNone shouldEqual None
    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer("Unnamed.Main", "Enso_Test.Test.Main", "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(2) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExecutionFailed(
          contextId,
          Api.ExecutionResult.Failure("Module Unnamed.Main not found.", None)
        )
      )
    )
  }

  it should "return error when constructor not found" in {
    val contents  = context.Main.code
    val mainFile  = context.writeMain(contents)
    val contextId = UUID.randomUUID()
    val requestId = UUID.randomUUID()

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // Set sources for the module
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(
              "Enso_Test.Test.Main",
              "Enso_Test.Test.Unexpected",
              "main"
            ),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(2) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExecutionFailed(
          contextId,
          Api.ExecutionResult.Failure(
            "Type Unexpected not found in module Enso_Test.Test.Main.",
            Some(mainFile)
          )
        )
      )
    )
  }

  it should "return error when method not found" in {
    val contents  = context.Main.code
    val mainFile  = context.writeMain(contents)
    val contextId = UUID.randomUUID()
    val requestId = UUID.randomUUID()

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // Set sources for the module
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(
              "Enso_Test.Test.Main",
              "Enso_Test.Test.Main",
              "ooops"
            ),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(2) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExecutionFailed(
          contextId,
          Api.ExecutionResult.Failure(
            "Object Main does not define method ooops in module Enso_Test.Test.Main.",
            Some(mainFile)
          )
        )
      )
    )
  }

  it should "return error not invocable" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"
    val metadata   = new Metadata("import Standard.Base.Data.Numbers\n")
    val code =
      """main = bar 40 2 123
        |
        |bar x y = x + y
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // Set sources for the module
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(2) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExecutionFailed(
          contextId,
          Api.ExecutionResult.Diagnostic.error(
            "Not_Invokable.Error",
            Some(mainFile),
            Some(model.Range(model.Position(1, 7), model.Position(1, 19))),
            None,
            Vector(
              Api.StackTraceElement(
                "Main.main",
                Some(mainFile),
                Some(
                  model.Range(model.Position(1, 7), model.Position(1, 19))
                ),
                None
              )
            )
          )
        )
      )
    )
  }

  it should "return error not invocable (pretty print)" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"
    val metadata   = new Metadata("import Standard.Base.Data.Numbers\n")
    val code =
      """from Standard.Base.Errors.Common import all
        |main = bar 40 2 123
        |
        |bar x y = x + y
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // Set sources for the module
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(2) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExecutionFailed(
          contextId,
          Api.ExecutionResult.Diagnostic.error(
            "Type error: expected a function, but got 42.",
            Some(mainFile),
            Some(model.Range(model.Position(2, 7), model.Position(2, 19))),
            None,
            Vector(
              Api.StackTraceElement(
                "Main.main",
                Some(mainFile),
                Some(
                  model.Range(model.Position(2, 7), model.Position(2, 19))
                ),
                None
              )
            )
          )
        )
      )
    )
  }

  it should "return error unresolved symbol" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"
    val metadata   = new Metadata
    val code =
      """main = bar .x .y
        |
        |bar one two = one + two
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // Set sources for the module
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveN(2) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExecutionFailed(
          contextId,
          Api.ExecutionResult.Diagnostic.error(
            "No_Such_Method.Error",
            Some(mainFile),
            Some(model.Range(model.Position(2, 14), model.Position(2, 23))),
            None,
            Vector(
              Api.StackTraceElement(
                "Main.bar",
                Some(mainFile),
                Some(
                  model.Range(model.Position(2, 14), model.Position(2, 23))
                ),
                None
              ),
              Api.StackTraceElement(
                "Main.main",
                Some(mainFile),
                Some(
                  model.Range(model.Position(0, 7), model.Position(0, 16))
                ),
                None
              )
            )
          )
        )
      )
    )
  }

  it should "return error unresolved symbol (pretty print)" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"
    val metadata   = new Metadata
    val code =
      """from Standard.Base.Errors.Common import all
        |main = bar .x .y
        |
        |bar one two = one + two
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // Set sources for the module
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(2) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExecutionFailed(
          contextId,
          Api.ExecutionResult.Diagnostic.error(
            "Method `+` of type Function could not be found.",
            Some(mainFile),
            Some(model.Range(model.Position(3, 14), model.Position(3, 23))),
            None,
            Vector(
              Api.StackTraceElement(
                "Main.bar",
                Some(mainFile),
                Some(
                  model.Range(model.Position(3, 14), model.Position(3, 23))
                ),
                None
              ),
              Api.StackTraceElement(
                "Main.main",
                Some(mainFile),
                Some(
                  model.Range(model.Position(1, 7), model.Position(1, 16))
                ),
                None
              )
            )
          )
        )
      )
    )
  }

  it should "return error unexpected type" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"
    val metadata   = new Metadata
    val code =
      """main = bar "one" 2
        |
        |bar x y = x + y
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // Set sources for the module
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveN(2) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExecutionFailed(
          contextId,
          Api.ExecutionResult.Diagnostic.error(
            "Type_Error.Error",
            Some(mainFile),
            Some(model.Range(model.Position(2, 10), model.Position(2, 15))),
            None,
            Vector(
              Api.StackTraceElement(
                "Main.bar",
                Some(mainFile),
                Some(
                  model.Range(model.Position(2, 10), model.Position(2, 15))
                ),
                None
              ),
              Api.StackTraceElement(
                "Main.main",
                Some(mainFile),
                Some(
                  model.Range(model.Position(0, 7), model.Position(0, 18))
                ),
                None
              )
            )
          )
        )
      )
    )
  }

  it should "return error unexpected type (pretty print)" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"
    val metadata   = new Metadata
    val code =
      """from Standard.Base.Errors.Common import all
        |main = bar "one" 2
        |
        |bar x y = x + y
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // Set sources for the module
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(2) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExecutionFailed(
          contextId,
          Api.ExecutionResult.Diagnostic.error(
            "Type error: Expected `str` to be Text, but got Integer.",
            Some(mainFile),
            Some(model.Range(model.Position(3, 10), model.Position(3, 15))),
            None,
            Vector(
              Api.StackTraceElement(
                "Main.bar",
                Some(mainFile),
                Some(
                  model.Range(model.Position(3, 10), model.Position(3, 15))
                ),
                None
              ),
              Api.StackTraceElement(
                "Main.main",
                Some(mainFile),
                Some(
                  model.Range(model.Position(1, 7), model.Position(1, 18))
                ),
                None
              )
            )
          )
        )
      )
    )
  }

  it should "return error method does not exist" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"
    val metadata   = new Metadata

    val code =
      """from Standard.Base.Data.Numbers import Number
        |
        |main = Number.pi
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // Set sources for the module
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(2) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExecutionFailed(
          contextId,
          Api.ExecutionResult.Diagnostic.error(
            "No_Such_Method.Error",
            Some(mainFile),
            Some(model.Range(model.Position(2, 7), model.Position(2, 16))),
            None,
            Vector(
              Api.StackTraceElement(
                "Main.main",
                Some(mainFile),
                Some(
                  model.Range(model.Position(2, 7), model.Position(2, 16))
                ),
                None
              )
            )
          )
        )
      )
    )
  }

  it should "return error method does not exist (pretty print)" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"
    val metadata   = new Metadata

    val code =
      """from Standard.Base.Data.Numbers import Number
        |from Standard.Base.Errors.Common import No_Such_Method
        |
        |main = Number.pi
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // Set sources for the module
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(2) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExecutionFailed(
          contextId,
          Api.ExecutionResult.Diagnostic.error(
            "Method `pi` of type Number.type could not be found.",
            Some(mainFile),
            Some(model.Range(model.Position(3, 7), model.Position(3, 16))),
            None,
            Vector(
              Api.StackTraceElement(
                "Main.main",
                Some(mainFile),
                Some(
                  model.Range(model.Position(3, 7), model.Position(3, 16))
                ),
                None
              )
            )
          )
        )
      )
    )
  }

  it should "return error with a stack trace" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"
    val metadata   = new Metadata("import Standard.Base.Data.Numbers\n\n")

    val code =
      """main =
        |    foo
        |
        |foo =
        |    x = bar
        |    x
        |bar =
        |    x = baz
        |    x
        |baz =
        |    x = 1 + .quux
        |    x
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // Set sources for the module
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(2) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExecutionFailed(
          contextId,
          Api.ExecutionResult.Diagnostic.error(
            "Type_Error.Error",
            None,
            Some(model.Range(model.Position(6, 18), model.Position(6, 43))),
            None,
            Vector(
              Api.StackTraceElement(
                "Integer.+",
                None,
                Some(model.Range(model.Position(6, 18), model.Position(6, 43))),
                None
              ),
              Api.StackTraceElement(
                "Main.baz",
                Some(mainFile),
                Some(
                  model.Range(model.Position(12, 8), model.Position(12, 17))
                ),
                None
              ),
              Api.StackTraceElement(
                "Main.bar",
                Some(mainFile),
                Some(
                  model.Range(model.Position(9, 8), model.Position(9, 11))
                ),
                None
              ),
              Api.StackTraceElement(
                "Main.foo",
                Some(mainFile),
                Some(
                  model.Range(model.Position(6, 8), model.Position(6, 11))
                ),
                None
              ),
              Api.StackTraceElement(
                "Main.main",
                Some(mainFile),
                Some(
                  model.Range(model.Position(3, 4), model.Position(3, 7))
                ),
                None
              )
            )
          )
        )
      )
    )
  }

  it should "return error with a stack trace (pretty print)" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"
    val metadata   = new Metadata("import Standard.Base.Data.Numbers\n")

    val code =
      """from Standard.Base.Errors.Common import all
        |main =
        |    foo
        |
        |foo =
        |    x = bar
        |    x
        |bar =
        |    x = baz
        |    x
        |baz =
        |    x = 1 + .quux
        |    x
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // Set sources for the module
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(2) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExecutionFailed(
          contextId,
          Api.ExecutionResult.Diagnostic.error(
            "Type error: Expected `that` to be Integer, but got Function.",
            None,
            Some(model.Range(model.Position(6, 18), model.Position(6, 43))),
            None,
            Vector(
              Api.StackTraceElement(
                "Integer.+",
                None,
                Some(model.Range(model.Position(6, 18), model.Position(6, 43))),
                None
              ),
              Api.StackTraceElement(
                "Main.baz",
                Some(mainFile),
                Some(
                  model.Range(model.Position(12, 8), model.Position(12, 17))
                ),
                None
              ),
              Api.StackTraceElement(
                "Main.bar",
                Some(mainFile),
                Some(
                  model.Range(model.Position(9, 8), model.Position(9, 11))
                ),
                None
              ),
              Api.StackTraceElement(
                "Main.foo",
                Some(mainFile),
                Some(
                  model.Range(model.Position(6, 8), model.Position(6, 11))
                ),
                None
              ),
              Api.StackTraceElement(
                "Main.main",
                Some(mainFile),
                Some(
                  model.Range(model.Position(3, 4), model.Position(3, 7))
                ),
                None
              )
            )
          )
        )
      )
    )
  }

  it should "return error when invoking System.exit" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"
    val metadata   = new Metadata
    val code =
      """import Standard.Base.System
        |
        |main =
        |    System.exit 42
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // Set sources for the module
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(2) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExecutionFailed(
          contextId,
          Api.ExecutionResult.Failure(
            "Exit was called with exit code 42.",
            Some(mainFile)
          )
        )
      )
    )
  }

  it should "return compiler warning unused variable" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"
    val metadata   = new Metadata

    val code =
      """main =
        |    x = 1
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // Set sources for the module
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveN(3) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExecutionUpdate(
          contextId,
          Seq(
            Api.ExecutionResult.Diagnostic.warning(
              "Unused variable x.",
              Some(mainFile),
              Some(model.Range(model.Position(1, 4), model.Position(1, 5))),
              None
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )
  }

  it should "return compiler warning unused argument" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"
    val metadata   = new Metadata

    val code =
      """foo x = 1
        |
        |main = 42
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // Set sources for the module
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveN(3) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExecutionUpdate(
          contextId,
          Seq(
            Api.ExecutionResult.Diagnostic.warning(
              "Unused function argument x.",
              Some(mainFile),
              Some(model.Range(model.Position(0, 4), model.Position(0, 5)))
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )
  }

  it should "return compiler error variable redefined" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"
    val metadata   = new Metadata

    val code =
      """main =
        |    x = 1
        |    x = 2
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // Set sources for the module
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveN(2) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExecutionUpdate(
          contextId,
          Seq(
            Api.ExecutionResult.Diagnostic.warning(
              "Unused variable x.",
              Some(mainFile),
              Some(model.Range(model.Position(1, 4), model.Position(1, 5)))
            ),
            Api.ExecutionResult.Diagnostic.error(
              "Variable x is being redefined.",
              Some(mainFile),
              Some(model.Range(model.Position(2, 4), model.Position(2, 9)))
            )
          )
        )
      )
    )
  }

  it should "return compiler error unrecognized token" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"
    val metadata   = new Metadata

    val code =
      """from Standard.Base import all
        |
        |main =
        |    x = Panic.catch_primitive ` .convert_to_dataflow_error
        |    IO.println x
        |    IO.println (x.catch Any .to_text)
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // Set sources for the module
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(3) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExecutionUpdate(
          contextId,
          Seq(
            Api.ExecutionResult.Diagnostic.error(
              "Unexpected expression.",
              Some(mainFile),
              Some(model.Range(model.Position(3, 30), model.Position(3, 31)))
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List(
      "(Error: (Syntax_Error.Error 'Unexpected expression'))",
      "(Syntax_Error.Error 'Unexpected expression')"
    )
  }

  it should "return compiler error syntax error" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"
    val metadata   = new Metadata

    val code =
      """import Standard.Base.IO
        |import Standard.Base.Panic.Panic
        |import Standard.Base.Any.Any
        |
        |main =
        |    x = Panic.catch_primitive () .convert_to_dataflow_error
        |    IO.println (x.catch Any .to_text)
        |
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // Set sources for the module
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(2) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExecutionUpdate(
          contextId,
          Seq(
            Api.ExecutionResult.Diagnostic.error(
              "Parentheses can't be empty.",
              Some(mainFile),
              Some(model.Range(model.Position(5, 30), model.Position(5, 32)))
            )
          )
        )
      )
    )
  }

  it should "return compiler error method overloads are not supported" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"
    val metadata   = new Metadata

    val code =
      """import Standard.Base.IO
        |
        |foo = 1
        |foo = 2
        |
        |main = IO.println foo
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // Set sources for the module
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(2) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      Api.Response(
        Api.ExecutionUpdate(
          contextId,
          Seq(
            Api.ExecutionResult.Diagnostic.error(
              "Method overloads are not supported: foo is defined multiple times in this module.",
              Some(mainFile),
              Some(model.Range(model.Position(3, 0), model.Position(3, 7)))
            )
          )
        )
      )
    )
  }

  it should "skip side effects when evaluating cached expression" in {
    val contents   = context.Main2.code
    val mainFile   = context.writeMain(contents)
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // Set sources for the module
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    val item1 = Api.StackItem.ExplicitCall(
      Api.MethodPointer(moduleName, moduleName, "main"),
      None,
      Vector()
    )
    context.send(
      Api.Request(requestId, Api.PushContextRequest(contextId, item1))
    )
    context.receiveNIgnoreStdLib(4) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.Main2.Update.mainY(contextId),
      context.Main2.Update.mainZ(contextId),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("I'm expensive!", "I'm more expensive!")

    // recompute
    context.send(
      Api.Request(
        requestId,
        Api.RecomputeContextRequest(contextId, None, None, Seq())
      )
    )
    context.receiveN(2) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.RecomputeContextResponse(contextId)),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List()
  }

  it should "rename a project" in {
    val contents   = context.Main.code
    val mainFile   = context.writeMain(contents)
    val moduleName = "Enso_Test.Test.Main"
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(
        requestId,
        Api.OpenFileRequest(mainFile, contents)
      )
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(5) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.Main.Update.mainX(contextId),
      context.Main.Update.mainY(contextId),
      context.Main.Update.mainZ(contextId),
      context.executionComplete(contextId)
    )

    // rename Test -> My Foo
    context.pkg.rename("My Foo")
    context.send(
      Api.Request(requestId, Api.RenameProject("Enso_Test", "Test", "My Foo"))
    )
    val renameProjectResponses = context.receiveN(6)
    renameProjectResponses should contain allOf (
      Api.Response(requestId, Api.ProjectRenamed("Test", "MyFoo", "My Foo")),
      context.Main.Update.mainX(contextId, typeChanged = false),
      TestMessages.update(
        contextId,
        context.Main.idMainY,
        ConstantsGen.INTEGER,
        Api.MethodCall(
          Api.MethodPointer("Enso_Test.MyFoo.Main", ConstantsGen.NUMBER, "foo")
        ),
        fromCache   = false,
        typeChanged = true
      ),
      context.Main.Update.mainZ(contextId, typeChanged = false),
      context.executionComplete(contextId)
    )
    renameProjectResponses.collect {
      case Api.Response(
            _,
            notification: Api.SuggestionsDatabaseModuleUpdateNotification
          ) =>
        notification.module shouldEqual moduleName
        notification.actions should contain theSameElementsAs Vector(
          Api.SuggestionsDatabaseAction.Clean(moduleName)
        )
    }

    // recompute existing stack
    context.send(
      Api.Request(
        requestId,
        Api.RecomputeContextRequest(contextId, None, None, Seq())
      )
    )
    context.receiveN(2) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.RecomputeContextResponse(contextId)),
      context.executionComplete(contextId)
    )

    // recompute invalidating all
    context.send(
      Api.Request(
        requestId,
        Api.RecomputeContextRequest(
          contextId,
          Some(Api.InvalidatedExpressions.All()),
          None,
          Seq()
        )
      )
    )
    context.receiveN(6) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.RecomputeContextResponse(contextId)),
      TestMessages.pending(
        contextId,
        context.Main.idMainX,
        context.Main.idMainY,
        context.Main.idMainZ,
        context.Main.idFooY,
        context.Main.idFooZ
      ),
      context.Main.Update.mainX(contextId, typeChanged = false),
      TestMessages.update(
        contextId,
        context.Main.idMainY,
        ConstantsGen.INTEGER,
        Api.MethodCall(
          Api.MethodPointer("Enso_Test.MyFoo.Main", ConstantsGen.NUMBER, "foo")
        ),
        fromCache   = false,
        typeChanged = false
      ),
      context.Main.Update.mainZ(contextId, typeChanged = false),
      context.executionComplete(contextId)
    )
  }

  it should "push and pop functions after renaming the project" in {
    val contents   = context.Main.code
    val mainFile   = context.writeMain(contents)
    val moduleName = "Enso_Test.Test.Main"
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    val item1 = Api.StackItem.ExplicitCall(
      Api.MethodPointer(moduleName, moduleName, "main"),
      None,
      Vector()
    )
    context.send(
      Api.Request(requestId, Api.PushContextRequest(contextId, item1))
    )
    context.receiveNIgnoreStdLib(5) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.Main.Update.mainX(contextId),
      context.Main.Update.mainY(contextId),
      context.Main.Update.mainZ(contextId),
      context.executionComplete(contextId)
    )

    // rename Test -> My Foo
    context.pkg.rename("My Foo")
    context.send(
      Api.Request(requestId, Api.RenameProject("Enso_Test", "Test", "My Foo"))
    )
    val renameProjectResponses = context.receiveN(6)
    renameProjectResponses should contain allOf (
      Api.Response(requestId, Api.ProjectRenamed("Test", "MyFoo", "My Foo")),
      context.Main.Update.mainX(contextId, typeChanged = false),
      TestMessages.update(
        contextId,
        context.Main.idMainY,
        ConstantsGen.INTEGER,
        Api.MethodCall(
          Api.MethodPointer("Enso_Test.MyFoo.Main", ConstantsGen.NUMBER, "foo")
        ),
        fromCache   = false,
        typeChanged = true
      ),
      context.Main.Update.mainZ(contextId, typeChanged = false),
      context.executionComplete(contextId)
    )
    renameProjectResponses.collect {
      case Api.Response(
            _,
            notification: Api.SuggestionsDatabaseModuleUpdateNotification
          ) =>
        notification.module shouldEqual moduleName
        notification.actions should contain theSameElementsAs Vector(
          Api.SuggestionsDatabaseAction.Clean(moduleName)
        )
    }

    // push foo call
    val item2 = Api.StackItem.LocalCall(context.Main.idMainY)
    context.send(
      Api.Request(requestId, Api.PushContextRequest(contextId, item2))
    )
    context.receiveNIgnoreStdLib(4) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.Main.Update.fooY(contextId),
      context.Main.Update.fooZ(contextId),
      context.executionComplete(contextId)
    )

    // pop foo call
    context.send(Api.Request(requestId, Api.PopContextRequest(contextId)))
    context.receiveN(4) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PopContextResponse(contextId)),
      TestMessages.update(
        contextId,
        context.Main.idMainY,
        ConstantsGen.INTEGER,
        Api.MethodCall(
          Api.MethodPointer("Enso_Test.MyFoo.Main", ConstantsGen.NUMBER, "foo")
        ),
        fromCache   = true,
        typeChanged = true
      ),
      context.Main.Update.mainZ(contextId, fromCache = true),
      context.executionComplete(contextId)
    )

    // pop main
    context.send(Api.Request(requestId, Api.PopContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.PopContextResponse(contextId))
    )

    // pop empty stack
    context.send(Api.Request(requestId, Api.PopContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.EmptyStackError(contextId))
    )
  }

  it should "send the type graph" in {
    val requestId                = UUID.randomUUID()
    val expectedGraph: TypeGraph = Types.getTypeHierarchy

    context.send(Api.Request(requestId, Api.GetTypeGraphRequest()))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.GetTypeGraphResponse(expectedGraph))
    )
  }

  it should "send updates for values annotated with warning" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"

    val metadata = new Metadata
    val idX1     = metadata.addItem(47, 14)
    val idX2     = metadata.addItem(71, 32)
    val idX3     = metadata.addItem(113, 32)

    val code =
      """from Standard.Base import all
        |
        |main =
        |    x1 = attach "x" "y"
        |    x2 = attach "x" (My_Warning.Value 42)
        |    x3 = attach "x" (My_Warning.Value x2)
        |    [x1, x2, x3]
        |
        |type My_Warning
        |    Value reason
        |
        |attach value warning =
        |    Warning.attach_with_stacktrace value warning Runtime.primitive_get_stack_trace
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnorePendingExpressionUpdates(
      5
    ) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages
        .update(
          contextId,
          idX1,
          ConstantsGen.TEXT,
          methodCall = Some(
            Api.MethodCall(Api.MethodPointer(moduleName, moduleName, "attach"))
          ),
          payload = Api.ExpressionUpdate.Payload.Value(
            Some(
              Api.ExpressionUpdate.Payload.Value.Warnings(1, Some("y"), false)
            )
          )
        ),
      TestMessages
        .update(
          contextId,
          idX2,
          ConstantsGen.TEXT,
          methodCall = Some(
            Api.MethodCall(Api.MethodPointer(moduleName, moduleName, "attach"))
          ),
          payload = Api.ExpressionUpdate.Payload.Value(
            Some(
              Api.ExpressionUpdate.Payload.Value
                .Warnings(1, Some("My_Warning.Value"), false)
            )
          )
        ),
      TestMessages
        .update(
          contextId,
          idX3,
          ConstantsGen.TEXT,
          methodCall = Some(
            Api.MethodCall(Api.MethodPointer(moduleName, moduleName, "attach"))
          ),
          payload = Api.ExpressionUpdate.Payload
            .Value(
              Some(
                Api.ExpressionUpdate.Payload.Value
                  .Warnings(2, Some("My_Warning.Value"), false)
              )
            )
        ),
      context.executionComplete(contextId)
    )
  }

  it should "send updates for expressions annotated with warning" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"

    val metadata = new Metadata
    val idX      = metadata.addItem(46, 71)
    val idY      = metadata.addItem(126, 5)
    val idRes    = metadata.addItem(136, 12)
    val code =
      """from Standard.Base import all
        |
        |main =
        |    x = Warning.attach_with_stacktrace 42 "y" Runtime.primitive_get_stack_trace
        |    y = x + 1
        |    IO.println y
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(5) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages
        .update(
          contextId,
          idX,
          ConstantsGen.INTEGER,
          payload = Api.ExpressionUpdate.Payload.Value(
            Some(
              Api.ExpressionUpdate.Payload.Value.Warnings(1, Some("y"), false)
            )
          )
        ),
      TestMessages
        .update(
          contextId,
          idY,
          ConstantsGen.INTEGER,
          methodCall = Some(
            Api.MethodCall(
              Api.MethodPointer(
                "Standard.Base.Data.Numbers",
                ConstantsGen.INTEGER,
                "+"
              )
            )
          ),
          payload = Api.ExpressionUpdate.Payload.Value(
            Some(
              Api.ExpressionUpdate.Payload.Value.Warnings(1, Some("y"), false)
            )
          )
        ),
      TestMessages
        .update(
          contextId,
          idRes,
          ConstantsGen.NOTHING,
          methodCall = Some(
            Api.MethodCall(
              Api.MethodPointer(
                "Standard.Base.IO",
                "Standard.Base.IO",
                "println"
              )
            )
          ),
          payload = Api.ExpressionUpdate.Payload.Value(
            Some(
              Api.ExpressionUpdate.Payload.Value.Warnings(1, Some("y"), false)
            )
          )
        ),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("43")
  }

  it should "send updates for values in array annotated with warning" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"

    val metadata = new Metadata
    val idMain   = metadata.addItem(37, 79)
    val code =
      """from Standard.Base import all
        |
        |main =
        |    [Warning.attach_with_stacktrace "x" "y" Runtime.primitive_get_stack_trace]
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(3) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(
        contextId,
        idMain,
        ConstantsGen.VECTOR,
        payload = Api.ExpressionUpdate.Payload.Value(
          Some(
            Api.ExpressionUpdate.Payload.Value.Warnings(1, Some("y"), false)
          )
        )
      ),
      context.executionComplete(contextId)
    )
  }

  it should "send pending updates for expressions" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"

    val metadata  = new Metadata("import Standard.Base.Data.Numbers\n\n")
    val x         = metadata.addItem(15, 1, "aa")
    val y         = metadata.addItem(25, 5, "ab")
    val `y_inc`   = metadata.addItem(25, 3, "ac")
    val `y_x`     = metadata.addItem(29, 1, "ad")
    val res       = metadata.addItem(35, 1, "ae")
    val `inc_res` = metadata.addItem(46, 5, "af")

    val code =
      """main =
        |    x = 1
        |    y = inc x
        |    y
        |
        |inc x = x + 1
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(7) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(contextId, x, ConstantsGen.INTEGER),
      TestMessages.update(contextId, `y_inc`, Constants.UNRESOLVED_SYMBOL),
      TestMessages.update(contextId, `y_x`, ConstantsGen.INTEGER),
      TestMessages.update(
        contextId,
        y,
        ConstantsGen.INTEGER,
        Api.MethodCall(Api.MethodPointer(moduleName, moduleName, "inc"))
      ),
      TestMessages.update(contextId, res, ConstantsGen.INTEGER),
      context.executionComplete(contextId)
    )

    // push inc call
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(contextId, Api.StackItem.LocalCall(y))
      )
    )
    context.receiveN(3) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(
        contextId,
        `inc_res`,
        ConstantsGen.INTEGER,
        methodCall = Some(
          Api.MethodCall(
            Api.MethodPointer(
              "Standard.Base.Data.Numbers",
              ConstantsGen.INTEGER,
              "+"
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )

    // pop inc call
    context.send(Api.Request(requestId, Api.PopContextRequest(contextId)))
    context.receiveN(4) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PopContextResponse(contextId)),
      TestMessages.update(
        contextId,
        y,
        ConstantsGen.INTEGER,
        Api.MethodCall(Api.MethodPointer(moduleName, moduleName, "inc")),
        fromCache   = true,
        typeChanged = true
      ),
      TestMessages.update(
        contextId,
        res,
        ConstantsGen.INTEGER,
        typeChanged = false
      ),
      context.executionComplete(contextId)
    )

    // push inc call
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(contextId, Api.StackItem.LocalCall(y))
      )
    )
    context.receiveN(3) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(
        contextId,
        `inc_res`,
        ConstantsGen.INTEGER,
        methodCall = Some(
          Api.MethodCall(
            Api.MethodPointer(
              "Standard.Base.Data.Numbers",
              ConstantsGen.INTEGER,
              "+"
            )
          )
        )
      ),
      context.executionComplete(contextId)
    )

    // Modify the inc method
    val at = metadata.assertInCode(
      code,
      model.Position(7, 12),
      model.Position(7, 13),
      "1"
    )
    context.send(
      Api.Request(
        Api.EditFileNotification(
          mainFile,
          Seq(
            TextEdit(
              at,
              "2"
            )
          ),
          execute = true,
          idMap   = None
        )
      )
    )
    context.receiveN(3) should contain theSameElementsAs Seq(
      TestMessages.pending(contextId, `inc_res`, `y_inc`, y, res),
      TestMessages.update(
        contextId,
        `inc_res`,
        ConstantsGen.INTEGER,
        methodCall = Some(
          Api.MethodCall(
            Api.MethodPointer(
              "Standard.Base.Data.Numbers",
              ConstantsGen.INTEGER,
              "+"
            )
          )
        ),
        typeChanged = false
      ),
      context.executionComplete(contextId)
    )

    // pop inc call
    context.send(Api.Request(requestId, Api.PopContextRequest(contextId)))
    context.receiveN(6) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PopContextResponse(contextId)),
      TestMessages.update(
        contextId,
        `y_inc`,
        Constants.UNRESOLVED_SYMBOL,
        typeChanged = false
      ),
      TestMessages.update(
        contextId,
        `y_x`,
        ConstantsGen.INTEGER,
        typeChanged = false
      ),
      TestMessages.update(
        contextId,
        y,
        ConstantsGen.INTEGER,
        Api.MethodCall(Api.MethodPointer(moduleName, moduleName, "inc")),
        fromCache   = false,
        typeChanged = false
      ),
      TestMessages.update(
        contextId,
        res,
        ConstantsGen.INTEGER,
        typeChanged = false
      ),
      context.executionComplete(contextId)
    )
  }

  it should "set execution environment" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"

    val metadata = new Metadata
    val idX      = metadata.addItem(46, 14)

    val code =
      """from Standard.Base import all
        |
        |main =
        |    x = "Hello World!"
        |    IO.println x
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(3) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(contextId, idX, ConstantsGen.TEXT),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("Hello World!")
    context.languageContext.getGlobalExecutionEnvironment.getName shouldEqual Api.ExecutionEnvironment
      .Design()
      .name

    // set execution environment
    context.send(
      Api.Request(
        requestId,
        Api.SetExecutionEnvironmentRequest(
          contextId,
          Api.ExecutionEnvironment.Live()
        )
      )
    )
    context.receiveNIgnoreStdLib(3) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.SetExecutionEnvironmentResponse(contextId)),
      TestMessages
        .update(contextId, idX, ConstantsGen.TEXT, typeChanged = false),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("Hello World!")
    context.languageContext.getGlobalExecutionEnvironment.getName shouldEqual Api.ExecutionEnvironment
      .Live()
      .name
  }

  it should "handle tailcalls" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"
    val metadata   = new Metadata("import Standard.Base.Data.Numbers\n\n")
    val code =
      """main =
        |    fac 10
        |
        |fac n =
        |    acc n v = if n <= 1 then v else
        |      @Tail_Call acc n-1 n*v
        |
        |    acc n 1
        |""".stripMargin.linesIterator.mkString("\n")

    val res = metadata.addItem(11, 6, "aa")

    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // Set sources for the module
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(3) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(
        contextId,
        res,
        ConstantsGen.INTEGER,
        methodCall =
          Some(Api.MethodCall(Api.MethodPointer(moduleName, moduleName, "fac")))
      ),
      context.executionComplete(contextId)
    )
  }

  it should "run main method with empty body" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"
    val code =
      """main =
        |""".stripMargin.linesIterator.mkString("\n")

    val mainFile = context.writeMain(code)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // Set sources for the module
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, code))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(2) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.executionComplete(contextId)
    )
  }

  it should "run methods with empty body ending with comment" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"
    val code =
      """foo =
        |    # TODO
        |
        |main =
        |    foo
        |""".stripMargin.linesIterator.mkString("\n")

    val mainFile = context.writeMain(code)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // Set sources for the module
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, code))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(2) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.executionComplete(contextId)
    )
  }

  it should "support file edit notification with IdMap" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"

    val xId = new UUID(0, 1)

    val code =
      """from Standard.Base import all
        |
        |main =
        |    x = 0
        |    IO.println x
        |""".stripMargin.linesIterator.mkString("\n")

    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // Create a new file
    val mainFile = context.writeMain(code)

    // Set sources for the module
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, code))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // Push new item on the stack to trigger the re-execution
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem
            .ExplicitCall(
              Api.MethodPointer(moduleName, moduleName, "main"),
              None,
              Vector()
            )
        )
      )
    )
    context.receiveNIgnoreStdLib(2) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("0")

    // Modify the file
    context.send(
      Api.Request(
        Api.EditFileNotification(
          mainFile,
          Seq(
            TextEdit(
              model.Range(model.Position(3, 8), model.Position(3, 9)),
              "\"Hello World!\""
            )
          ),
          execute = true,
          idMap   = Some(model.IdMap(Vector(model.Span(46, 60) -> xId)))
        )
      )
    )
    context.receiveN(2) shouldEqual Seq(
      TestMessages.update(contextId, xId, ConstantsGen.TEXT),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("Hello World!")
  }

  it should "edit local call with cached self" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"

    val metadata   = new Metadata
    val idX        = metadata.addItem(46, 11, "aa")
    val idSelfMain = metadata.addItem(46, 4, "ab")
    val idIncZ     = new UUID(0, 1)

    val code =
      """from Standard.Base import all
        |
        |main =
        |    x = Main.inc 41
        |    IO.println x
        |
        |inc a =
        |    y = 1
        |    r = a + y
        |    r
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(4) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(
        contextId,
        idX,
        ConstantsGen.INTEGER,
        methodCall =
          Some(Api.MethodCall(Api.MethodPointer(moduleName, moduleName, "inc")))
      ),
      TestMessages.update(contextId, idSelfMain, moduleName),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("42")

    // push inc call
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(contextId, Api.StackItem.LocalCall(idX))
      )
    )
    context.receiveNIgnoreStdLib(2) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("42")

    // Modify the file
    context.send(
      Api.Request(
        Api.EditFileNotification(
          mainFile,
          Seq(
            model.TextEdit(
              model.Range(model.Position(8, 4), model.Position(8, 9)),
              "z = 2\n    r = a + z"
            )
          ),
          execute = true,
          idMap   = Some(model.IdMap(Vector(model.Span(102, 103) -> idIncZ)))
        )
      )
    )
    context.receiveNIgnorePendingExpressionUpdates(2, 10) shouldEqual Seq(
      TestMessages.update(
        contextId,
        idIncZ,
        ConstantsGen.INTEGER
      ),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("44")
  }

  it should "edit two local calls with cached self" in {
    val contextId  = UUID.randomUUID()
    val requestId  = UUID.randomUUID()
    val moduleName = "Enso_Test.Test.Main"

    val metadata    = new Metadata
    val idX         = metadata.addItem(46, 10, "aa")
    val idY         = metadata.addItem(65, 10, "ab")
    val idXSelfMain = metadata.addItem(46, 4, "ac")
    val idYSelfMain = metadata.addItem(65, 4, "ad")
    val idIncZ      = new UUID(0, 1)

    val code =
      """from Standard.Base import all
        |
        |main =
        |    x = Main.inc 3
        |    y = Main.inc 7
        |    IO.println x+y
        |
        |inc a =
        |    y = 1
        |    r = a + y
        |    r
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // open file
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(
          contextId,
          Api.StackItem.ExplicitCall(
            Api.MethodPointer(moduleName, moduleName, "main"),
            None,
            Vector()
          )
        )
      )
    )
    context.receiveNIgnoreStdLib(6) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      TestMessages.update(
        contextId,
        idX,
        ConstantsGen.INTEGER,
        methodCall =
          Some(Api.MethodCall(Api.MethodPointer(moduleName, moduleName, "inc")))
      ),
      TestMessages.update(
        contextId,
        idY,
        ConstantsGen.INTEGER,
        methodCall =
          Some(Api.MethodCall(Api.MethodPointer(moduleName, moduleName, "inc")))
      ),
      TestMessages.update(contextId, idXSelfMain, moduleName),
      TestMessages.update(contextId, idYSelfMain, moduleName),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("12")

    // push inc call
    context.send(
      Api.Request(
        requestId,
        Api.PushContextRequest(contextId, Api.StackItem.LocalCall(idX))
      )
    )
    context.receiveNIgnoreStdLib(2) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("12")

    // Modify the file
    context.send(
      Api.Request(
        Api.EditFileNotification(
          mainFile,
          Seq(
            model.TextEdit(
              model.Range(model.Position(9, 4), model.Position(9, 9)),
              "z = 2\n    r = a + z"
            )
          ),
          execute = true,
          idMap   = Some(model.IdMap(Vector(model.Span(122, 123) -> idIncZ)))
        )
      )
    )
    context.receiveNIgnorePendingExpressionUpdates(2, 10) shouldEqual Seq(
      TestMessages.update(
        contextId,
        idIncZ,
        ConstantsGen.INTEGER
      ),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("16")

    // pop the inc call
    context.send(Api.Request(requestId, Api.PopContextRequest(contextId)))

    context.receiveNIgnoreStdLib(6) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PopContextResponse(contextId)),
      TestMessages.update(
        contextId,
        idX,
        ConstantsGen.INTEGER,
        fromCache   = false,
        typeChanged = false,
        methodCall =
          Some(Api.MethodCall(Api.MethodPointer(moduleName, moduleName, "inc")))
      ),
      TestMessages.update(
        contextId,
        idY,
        ConstantsGen.INTEGER,
        fromCache   = false,
        typeChanged = false,
        methodCall =
          Some(Api.MethodCall(Api.MethodPointer(moduleName, moduleName, "inc")))
      ),
      TestMessages.update(
        contextId,
        idXSelfMain,
        moduleName,
        fromCache   = true,
        typeChanged = false
      ),
      TestMessages.update(
        contextId,
        idYSelfMain,
        moduleName,
        fromCache   = true,
        typeChanged = false
      ),
      context.executionComplete(contextId)
    )
    context.consumeOut shouldEqual List("16")
  }

  it should "resolve multiple autoscoped atomconstructor with no IDs initially" in {
    val contextId       = UUID.randomUUID()
    val requestId       = UUID.randomUUID()
    val moduleName      = "Enso_Test.Test.Main"
    val moduleNameLib   = "Enso_Test.Test.Lib"
    val moduleNameTypes = "Enso_Test.Test.Types"
    val metadata        = new Metadata

    val idS    = UUID.randomUUID()
    val idX    = UUID.randomUUID()
    val idAArg = UUID.randomUUID()
    val idBArg = UUID.randomUUID()
    val idRes  = UUID.randomUUID()

    val typesMetadata = new Metadata
    val codeTypes = typesMetadata.appendToCode(
      """type Foo
        |    A
        |
        |type Bar
        |    B
        |""".stripMargin.linesIterator.mkString("\n")
    )
    val typesFile = context.writeInSrcDir("Types", codeTypes)

    val libMetadata = new Metadata
    val codeLib = libMetadata.appendToCode(
      """from project.Types import Foo, Bar
        |from Standard.Base import all
        |
        |type Singleton
        |    S value
        |
        |    test : Foo -> Bar -> Number
        |    test self (x : Foo = ..A) (y : Bar = ..B) =
        |        Singleton.from_test x y
        |
        |    from_test : Foo -> Bar -> Number
        |    from_test (x : Foo = ..A) (y : Bar = ..B) =
        |        _ = x
        |        _ = y
        |        42
        |""".stripMargin.linesIterator.mkString("\n")
    )

    val libFile = context.writeInSrcDir("Lib", codeLib)

    val code =
      """from project.Lib import Singleton
        |
        |main =
        |    s = Singleton.S 1
        |    x = s.test ..A ..B
        |    x
        |""".stripMargin.linesIterator.mkString("\n")
    val contents = metadata.appendToCode(code)
    val mainFile = context.writeMain(contents)

    // create context
    context.send(Api.Request(requestId, Api.CreateContextRequest(contextId)))
    context.receive shouldEqual Some(
      Api.Response(requestId, Api.CreateContextResponse(contextId))
    )

    // Open files
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(typesFile, codeTypes))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(libFile, codeLib))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )
    context.send(
      Api.Request(requestId, Api.OpenFileRequest(mainFile, contents))
    )
    context.receive shouldEqual Some(
      Api.Response(Some(requestId), Api.OpenFileResponse)
    )

    // push main
    val item1 = Api.StackItem.ExplicitCall(
      Api.MethodPointer(moduleName, "Enso_Test.Test.Main", "main"),
      None,
      Vector()
    )
    context.send(
      Api.Request(requestId, Api.PushContextRequest(contextId, item1))
    )
    context.receiveNIgnorePendingExpressionUpdates(
      2
    ) should contain theSameElementsAs Seq(
      Api.Response(requestId, Api.PushContextResponse(contextId)),
      context.executionComplete(contextId)
    )

    context.send(
      Api.Request(
        Api.EditFileNotification(
          mainFile,
          Seq(),
          execute = true,
          idMap = Some(
            model.IdMap(
              Vector(
                model.Span(50, 63) -> idS,
                model.Span(72, 86) -> idX,
                model.Span(79, 82) -> idAArg,
                model.Span(83, 86) -> idBArg,
                model.Span(91, 92) -> idRes
              )
            )
          )
        )
      )
    )
    val afterIdMapUpdate = context.receiveN(6)

    afterIdMapUpdate shouldEqual Seq(
      TestMessages.update(
        contextId,
        idS,
        s"$moduleNameLib.Singleton",
        methodCall = Some(
          Api.MethodCall(
            Api
              .MethodPointer(moduleNameLib, s"$moduleNameLib.Singleton", "S")
          )
        ),
        payload = Api.ExpressionUpdate.Payload.Value(None)
      ),
      TestMessages.update(
        contextId,
        idAArg,
        s"$moduleNameTypes.Foo",
        methodCall = Some(
          Api.MethodCall(
            Api
              .MethodPointer(
                moduleNameTypes,
                s"$moduleNameTypes.Foo",
                "A"
              )
          )
        ),
        payload = Api.ExpressionUpdate.Payload.Value(None)
      ),
      TestMessages.update(
        contextId,
        idBArg,
        s"$moduleNameTypes.Bar",
        methodCall = Some(
          Api.MethodCall(
            Api
              .MethodPointer(
                moduleNameTypes,
                s"$moduleNameTypes.Bar",
                "B"
              )
          )
        ),
        payload = Api.ExpressionUpdate.Payload.Value(None)
      ),
      TestMessages.update(
        contextId,
        idX,
        s"Standard.Base.Data.Numbers.Integer",
        methodCall = Some(
          Api.MethodCall(
            Api
              .MethodPointer(
                moduleNameLib,
                s"$moduleNameLib.Singleton",
                "test"
              )
          )
        ),
        payload = Api.ExpressionUpdate.Payload.Value(None)
      ),
      TestMessages.update(
        contextId,
        idRes,
        s"Standard.Base.Data.Numbers.Integer",
        payload = Api.ExpressionUpdate.Payload.Value(None)
      ),
      context.executionComplete(contextId)
    )
  }

}
object RuntimeServerTest {

  trait TestMain {
    object Main {

      val metadata = new Metadata

      val idMainX = metadata.addItem(63, 1, "aa1")
      val idMainY = metadata.addItem(73, 7, "aa2")
      val idMainZ = metadata.addItem(89, 5, "aa3")
      val idFooY  = metadata.addItem(133, 8, "ff2")
      val idFooZ  = metadata.addItem(150, 5, "ff3")

      def code =
        metadata.appendToCode(
          """
            |from Standard.Base.Data.Numbers import    all
            |
            |main =
            |    x = 6
            |    y = x.foo 5
            |    z = y + 5
            |    z
            |
            |Number.foo self = x ->
            |    y = self + 3
            |    z = y * x
            |    z
            |""".stripMargin.linesIterator.mkString("\n")
        )

      object Update {

        def mainX(
          contextId: UUID,
          fromCache: Boolean   = false,
          typeChanged: Boolean = true
        ): Api.Response =
          TestMessages.update(
            contextId,
            Main.idMainX,
            ConstantsGen.INTEGER,
            fromCache,
            typeChanged,
            methodCall = None
          )

        def pendingZ(): Api.ExpressionUpdate =
          Api.ExpressionUpdate(
            Main.idFooZ,
            None,
            None,
            Vector(),
            true,
            false,
            Api.ExpressionUpdate.Payload.Pending(None, None)
          )

        def pendingY(): Api.ExpressionUpdate =
          Api.ExpressionUpdate(
            Main.idFooY,
            None,
            None,
            Vector(),
            true,
            false,
            Api.ExpressionUpdate.Payload.Pending(None, None)
          )

        def mainY(
          contextId: UUID,
          fromCache: Boolean   = false,
          typeChanged: Boolean = true
        ): Api.Response =
          TestMessages.update(
            contextId,
            Main.idMainY,
            ConstantsGen.INTEGER,
            Api.MethodCall(
              Api.MethodPointer(
                "Enso_Test.Test.Main",
                ConstantsGen.NUMBER,
                "foo"
              )
            ),
            fromCache,
            typeChanged
          )

        def mainZ(
          contextId: UUID,
          fromCache: Boolean   = false,
          typeChanged: Boolean = true
        ): Api.Response =
          TestMessages.update(
            contextId,
            Main.idMainZ,
            ConstantsGen.INTEGER,
            Api.MethodCall(
              Api.MethodPointer(
                "Standard.Base.Data.Numbers",
                ConstantsGen.INTEGER,
                "+"
              )
            ),
            fromCache,
            typeChanged
          )

        def fooY(
          contextId: UUID,
          fromCache: Boolean   = false,
          typeChanged: Boolean = true
        ): Api.Response =
          TestMessages.update(
            contextId,
            Main.idFooY,
            ConstantsGen.INTEGER,
            Api.MethodCall(
              Api.MethodPointer(
                "Standard.Base.Data.Numbers",
                ConstantsGen.INTEGER,
                "+"
              )
            ),
            fromCache,
            typeChanged
          )

        def fooZ(
          contextId: UUID,
          fromCache: Boolean   = false,
          typeChanged: Boolean = true
        ): Api.Response =
          TestMessages.update(
            contextId,
            Main.idFooZ,
            ConstantsGen.INTEGER,
            Api.MethodCall(
              Api.MethodPointer(
                "Standard.Base.Data.Numbers",
                ConstantsGen.INTEGER,
                "*"
              )
            ),
            fromCache,
            typeChanged
          )
      }
    }

    object Main2 {

      val metadata = new Metadata
      val idMainY  = metadata.addItem(178, 5)
      val idMainZ  = metadata.addItem(192, 5)

      val code = metadata.appendToCode(
        """from Standard.Base import all
          |
          |foo = arg ->
          |    IO.println "I'm expensive!"
          |    arg + 5
          |
          |bar = arg ->
          |    IO.println "I'm more expensive!"
          |    arg * 5
          |
          |main =
          |    x = 10
          |    y = foo x
          |    z = bar y
          |    z
          |""".stripMargin.linesIterator.mkString("\n")
      )

      object Update {

        def mainY(contextId: UUID) =
          TestMessages.update(
            contextId,
            idMainY,
            ConstantsGen.INTEGER,
            Api.MethodCall(
              Api.MethodPointer(
                "Enso_Test.Test.Main",
                "Enso_Test.Test.Main",
                "foo"
              )
            )
          )

        def mainZ(contextId: UUID) =
          TestMessages.update(
            contextId,
            idMainZ,
            ConstantsGen.INTEGER,
            Api.MethodCall(
              Api.MethodPointer(
                "Enso_Test.Test.Main",
                "Enso_Test.Test.Main",
                "bar"
              )
            )
          )
      }
    }
  }
}
