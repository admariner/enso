package org.enso.compiler.test

import org.enso.compiler.context.{FreshNameSupply, InlineContext, ModuleContext}
import org.enso.compiler.core.EnsoParser
import org.enso.compiler.core.Implicits.AsMetadata
import org.enso.compiler.core.ir.{Expression, Module}
import org.enso.compiler.core.ir.MetadataStorage.MetadataPair
import org.enso.compiler.data.BindingsMap.ModuleReference
import org.enso.compiler.data.{BindingsMap, CompilerConfig}
import org.enso.compiler.pass.analyse.BindingAnalysis
import org.enso.compiler.pass.{
  PassConfiguration,
  PassManager,
  PassManagerTestUtils
}
import org.enso.interpreter.runtime
import org.enso.interpreter.runtime.ModuleTestUtils
import org.enso.compiler.context.LocalScope
import org.enso.pkg.QualifiedName
import org.enso.common.CompilationStage

/** A reduced version of [[org.enso.compiler.test.CompilerRunner]] that avoids introducing a cyclic dependency
  * to `runtime-instrument-common` subject.
  */
trait CompilerTestSetup {
  // === IR Utilities =========================================================

  /** Provides an extension method allowing the running of a specified list of
    * passes on the provided IR.
    *
    * @param ir the IR to run the passes on
    */
  implicit private class RunPassesOnModule(ir: Module) {

    /** Executes the passes using `passManager` on the input [[ir]].
      *
      * @param passManager the pass configuration
      * @param moduleContext the module context it is executing in
      * @return the result of executing the passes in `passManager` on [[ir]]
      */
    def runPasses(
      passManager: PassManager,
      moduleContext: ModuleContext
    ): Module = {
      val passGroups = PassManagerTestUtils.getPasses(passManager)
      val runtimeMod = runtime.Module.fromCompilerModule(moduleContext.module)
      passGroups.foldLeft(ir)((curIr, group) => {
        // Before a PassGroup is run on a module, we need to explicitly set the
        // IR on the runtime module, as the pass manager will not do this for us.
        // This is to ensure consistency between the curIr and IR stored in moduleContext
        ModuleTestUtils.unsafeSetIr(runtimeMod, curIr)
        val newIr =
          passManager.runPassesOnModule(curIr, moduleContext, group, None)
        newIr
      })
    }
  }

  /** Provides an extension method allowing the running of a specified list of
    * passes on the provided IR.
    *
    * @param ir the IR to run the passes on
    */
  implicit private class RunPassesOnExpression(ir: Expression) {

    /** Executes the passes using `passManager` on the input [[ir]].
      *
      * @param passManager the pass configuration
      * @param inlineContext the inline context it is executing in
      * @return the result of executing the passes in `passManager` on [[ir]]
      */
    def runPasses(
      passManager: PassManager,
      inlineContext: InlineContext
    ): Expression = {
      passManager.runPassesInline(ir, inlineContext)
    }
  }

  /** Adds an extension method to preprocess the source as IR.
    *
    * @param source the source code to preprocess
    */
  implicit class Preprocess(source: String)(implicit
    passManager: PassManager
  ) {

    /** Translates the source code into appropriate IR for testing this pass.
      *
      * @return IR appropriate for testing the alias analysis pass as a module
      */
    def preprocessModule(implicit moduleContext: ModuleContext): Module = {
      EnsoParser.compile(source).runPasses(passManager, moduleContext)
    }

    /** Translates the source code into appropriate IR for testing this pass
      *
      * @return IR appropriate for testing the alias analysis pass as an
      *         expression
      */
    def preprocessExpression(implicit
      inlineContext: InlineContext
    ): Option[Expression] = {
      EnsoParser
        .compileInline(source)
        .map(_.runPasses(passManager, inlineContext))
    }
  }

  // === IR Testing Utils =====================================================

  /** Builds a module context with a mocked module for testing purposes.
    *
    * @param moduleName the name of the test module.
    * @param freshNameSupply the fresh name supply to use in tests.
    * @param passConfiguration any additional pass configuration.
    * @return an instance of module context.
    */
  def buildModuleContext(
    moduleName: QualifiedName                    = QualifiedName.simpleName("Test_Module"),
    freshNameSupply: Option[FreshNameSupply]     = None,
    passConfiguration: Option[PassConfiguration] = None,
    compilerConfig: CompilerConfig               = defaultConfig,
    isGeneratingDocs: Boolean                    = false
  ): ModuleContext = buildModuleContextModule(
    moduleName,
    freshNameSupply,
    passConfiguration,
    compilerConfig,
    isGeneratingDocs
  )._1

  /** Builds a module context with a mocked module for testing purposes.
    *
    * @param moduleName the name of the test module.
    * @param freshNameSupply the fresh name supply to use in tests.
    * @param passConfiguration any additional pass configuration.
    * @return an pair of module context and module.
    */
  def buildModuleContextModule(
    moduleName: QualifiedName                    = QualifiedName.simpleName("Test_Module"),
    freshNameSupply: Option[FreshNameSupply]     = None,
    passConfiguration: Option[PassConfiguration] = None,
    compilerConfig: CompilerConfig               = defaultConfig,
    isGeneratingDocs: Boolean                    = false
  ): (ModuleContext, runtime.Module) = {
    val mod = runtime.Module.empty(moduleName, null)
    val ctx = ModuleContext(
      module            = mod.asCompilerModule(),
      freshNameSupply   = freshNameSupply,
      passConfiguration = passConfiguration,
      compilerConfig    = compilerConfig,
      isGeneratingDocs  = isGeneratingDocs
    )
    (ctx, mod)
  }

  /** Builds an inline context with a mocked module for testing purposes.
    *
    * @param localScope the local scope for variable resolution.
    * @param isInTailPosition whether the expression is being evaluated in
    *                         a tail position.
    * @param freshNameSupply the fresh name supply to use for name generation.
    * @param passConfiguration any additional pass configuration.
    * @return an instance of inline context.
    */
  def buildInlineContext(
    localScope: Option[LocalScope]               = None,
    isInTailPosition: Option[Boolean]            = None,
    freshNameSupply: Option[FreshNameSupply]     = None,
    passConfiguration: Option[PassConfiguration] = None,
    compilerConfig: CompilerConfig               = defaultConfig
  ): InlineContext = {
    val mod =
      runtime.Module.empty(QualifiedName.simpleName("Test_Module"), null)
    ModuleTestUtils.unsafeSetIr(
      mod,
      Module(List(), List(), List(), false, null)
        .updateMetadata(
          new MetadataPair(
            BindingAnalysis,
            BindingsMap(
              List(),
              ModuleReference.Concrete(mod.asCompilerModule())
            )
          )
        )
    )
    ModuleTestUtils.unsafeSetCompilationStage(
      mod,
      CompilationStage.AFTER_CODEGEN
    )
    val mc = ModuleContext(
      module         = mod.asCompilerModule(),
      compilerConfig = compilerConfig
    )
    InlineContext(
      moduleContext     = mc,
      freshNameSupply   = freshNameSupply,
      passConfiguration = passConfiguration,
      localScope        = localScope,
      isInTailPosition  = isInTailPosition,
      compilerConfig    = compilerConfig
    )
  }

  val defaultConfig: CompilerConfig = CompilerConfig()
}
