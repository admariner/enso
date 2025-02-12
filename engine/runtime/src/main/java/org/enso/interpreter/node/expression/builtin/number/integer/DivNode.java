package org.enso.interpreter.node.expression.builtin.number.integer;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import org.enso.interpreter.dsl.BuiltinMethod;
import org.enso.interpreter.node.expression.builtin.number.utils.BigIntegerOps;
import org.enso.interpreter.runtime.EnsoContext;
import org.enso.interpreter.runtime.error.DataflowError;
import org.enso.interpreter.runtime.number.EnsoBigInteger;

@BuiltinMethod(type = "Integer", name = "div", description = "Division of numbers.")
public abstract class DivNode extends IntegerNode.Binary {

  @Override
  abstract Object executeBinary(Object own, Object that);

  static DivNode build() {
    return DivNodeGen.create();
  }

  @Specialization
  Object doLong(long self, long that) {
    try {
      return self / that;
    } catch (ArithmeticException e) {
      return DataflowError.withDefaultTrace(
          EnsoContext.get(this).getBuiltins().error().getDivideByZeroError(), this);
    }
  }

  @Specialization
  Object doBigInteger(long self, EnsoBigInteger that) {
    // No need to trap, as 0 is never represented as an EnsoBigInteger.
    return 0L;
  }

  @Specialization
  Object doLong(EnsoBigInteger self, long that) {
    try {
      return toEnsoNumberNode().execute(BigIntegerOps.divide(self.getValue(), that));
    } catch (ArithmeticException e) {
      return DataflowError.withDefaultTrace(
          EnsoContext.get(this).getBuiltins().error().getDivideByZeroError(), this);
    }
  }

  @Specialization
  Object doBigInteger(EnsoBigInteger self, EnsoBigInteger that) {
    // No need to trap, as 0 is never represented as an EnsoBigInteger.
    return toEnsoNumberNode().execute(BigIntegerOps.divide(self.getValue(), that.getValue()));
  }

  @Fallback
  Object doOther(Object self, Object that) {
    throw throwTypeErrorIfNotInt(self, that);
  }
}
