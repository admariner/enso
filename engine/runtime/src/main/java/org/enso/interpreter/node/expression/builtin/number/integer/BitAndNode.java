package org.enso.interpreter.node.expression.builtin.number.integer;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import org.enso.interpreter.dsl.BuiltinMethod;
import org.enso.interpreter.node.expression.builtin.number.utils.BigIntegerOps;
import org.enso.interpreter.runtime.number.EnsoBigInteger;

@BuiltinMethod(type = "Integer", name = "bit_and", description = "Bitwise and.")
public abstract class BitAndNode extends IntegerNode.Binary {

  @Override
  abstract Object executeBinary(Object own, Object that);

  public static BitAndNode build() {
    return BitAndNodeGen.create();
  }

  @Specialization
  long doLongLong(long self, long that) {
    return self & that;
  }

  @Specialization
  Object doLongBigInt(long self, EnsoBigInteger that) {
    return toEnsoNumberNode().execute(BigIntegerOps.bitAnd(self, that.getValue()));
  }

  @Specialization
  Object doBigIntLong(EnsoBigInteger self, long that) {
    return toEnsoNumberNode().execute(BigIntegerOps.bitAnd(self.getValue(), that));
  }

  @Specialization
  Object doBigIntBigInt(EnsoBigInteger self, EnsoBigInteger that) {
    return toEnsoNumberNode().execute(BigIntegerOps.bitAnd(self.getValue(), that.getValue()));
  }

  @Fallback
  Object doOther(Object self, Object that) {
    throw throwTypeErrorIfNotInt(self, that);
  }
}
