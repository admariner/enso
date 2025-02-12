module org.enso.runtime.compiler {
  requires java.logging;
  requires scala.library;

  requires org.enso.engine.common;
  requires org.enso.editions;
  requires org.enso.pkg;
  requires org.enso.runtime.compiler.dump;
  requires org.enso.runtime.parser;
  requires static org.enso.persistance;
  requires org.enso.syntax;
  requires org.enso.scala.wrapper;

  requires org.openide.util.lookup.RELEASE180;
  requires org.slf4j;

  exports org.enso.compiler;
  exports org.enso.compiler.context;
  exports org.enso.compiler.data;
  exports org.enso.compiler.docs;
  exports org.enso.compiler.exception;
  exports org.enso.compiler.pass;
  exports org.enso.compiler.pass.analyse;
  exports org.enso.compiler.pass.analyse.alias;
  exports org.enso.compiler.pass.analyse.alias.graph;
  exports org.enso.compiler.pass.analyse.types;
  exports org.enso.compiler.pass.desugar;
  exports org.enso.compiler.pass.lint;
  exports org.enso.compiler.pass.optimise;
  exports org.enso.compiler.pass.resolve;
  exports org.enso.compiler.phase;
  exports org.enso.compiler.phase.exports;
  exports org.enso.compiler.refactoring;
  exports org.enso.compiler.common;

  uses org.enso.compiler.dump.service.IRDumpFactoryService;
}
