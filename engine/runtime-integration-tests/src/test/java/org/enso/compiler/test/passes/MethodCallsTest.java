package org.enso.compiler.test.passes;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import org.enso.compiler.core.ir.MetadataStorage;
import org.enso.compiler.core.ir.Module;
import org.enso.compiler.core.ir.Name;
import org.enso.compiler.core.ir.expression.Application;
import org.enso.compiler.data.BindingsMap;
import org.enso.compiler.pass.resolve.MethodCalls$;
import org.enso.test.utils.ContextUtils;
import org.junit.Test;
import scala.Option;

public class MethodCallsTest {
  @Test
  public void resolveSimpleModuleMethod() {
    var code =
        """
        module_method x = x
        main =
            Test.module_method 42
        """;
    var ctx = ContextUtils.createDefaultContext();
    var ir = ContextUtils.compileModule(ctx, code, "Test");
    var methodCall = findMethodCall(ir, "module_method");
    var meta = methodCall.function().passData().get(MethodCalls$.MODULE$);
    assertThat(meta.isDefined(), is(true));
    var metaTarget = ((BindingsMap.Resolution) meta.get()).target();
    assertThat(metaTarget, is(instanceOf(BindingsMap.ResolvedModuleMethod.class)));
  }

  private Application.Prefix findMethodCall(Module ir, String methodName) {
    var res =
        ir.preorder()
            .find(
                childIr -> {
                  if (childIr instanceof Application.Prefix app
                      && app.function() instanceof Name.Literal lit) {
                    return lit.name().equals(methodName);
                  }
                  return false;
                });
    assertThat(res.isDefined(), is(true));
    return (Application.Prefix) res.get();
  }

  @Test
  public void interningNameLiteralStrings() {
    var interned = "same_name";
    var n1 = new String(interned);
    var n2 = new String(interned);
    assertNotSame(n1, n2);

    var meta = new MetadataStorage();
    var l1 = new Name.Literal(n1, false, null, Option.empty(), meta);
    var l2 = new Name.Literal(n2, false, null, Option.empty(), meta);

    assertEquals("Literals are structurally equal", l1, l2);
    assertSame("Literals share the same name string", l1.name(), l2.name());
    assertSame("It is the interned string", l1.name(), interned);
  }
}
