package org.enso.interpreter.test.instrument;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import org.enso.common.DebugServerInfo;
import org.enso.test.utils.ContextUtils;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.hamcrest.core.AllOf;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class DebugServerInspectTest {
  private static Context ctx;
  private static ByteArrayOutputStream out = new ByteArrayOutputStream();
  private static ByteArrayOutputStream err = new ByteArrayOutputStream();

  @BeforeClass
  public static void initContext() throws Exception {
    var b = ContextUtils.defaultContextBuilder().out(out).err(err);
    b.option(DebugServerInfo.METHOD_BREAKPOINT_OPTION, "ScriptTest.inspect");
    ctx = b.build();
  }

  @AfterClass
  public static void closeContext() throws Exception {
    ctx.close();
    ctx = null;
    out = null;
    err = null;
  }

  @Before
  public void cleanSteams() {
    out.reset();
    err.reset();
  }

  @Test
  public void listingVariablesWithWarnings() throws Exception {
    var code =
        """
        from Standard.Base import all

        inspect =
            j = 1
            d = Warning.attach "doubled value" 2
            t = j + d
            v = [j, d, t]
            v
        """;
    var r = ContextUtils.evalModule(ctx, code, "ScriptTest.enso", "inspect");
    assertTrue("Got array back: " + r, r.hasArrayElements());
    assertEquals("Got three elements", 3, r.getArraySize());
    assertEquals("One", 1, r.getArrayElement(0).asInt());
    assertEquals("Two", 2, r.getArrayElement(1).asInt());
    assertEquals("Three", 3, r.getArrayElement(2).asInt());
    assertEquals("No output printed", "", out.toString());
    assertThat(
        "Stderr contains some warnings",
        err.toString(),
        AllOf.allOf(
            containsString("d = 2"),
            containsString("t = 3"),
            containsString("doubled value"),
            not(containsString("j = 1"))));

    var at1 = err.toString().indexOf("d = 2");
    assertNotEquals("d = 2 found", -1, at1);
    var at2 = err.toString().indexOf("d = 2", at1 + 1);
    assertEquals("d = 2 not found for the second time", -1, at2);
  }

  @Test
  public void panicOnError() throws Exception {
    var code =
        """
        from Standard.Base import all

        inspect =
            j = 1
            d = Error.throw 2
            t = j + d
            v = [j, d, t]
            v
        """;
    var r = ContextUtils.evalModule(ctx, code, "ScriptTest.enso", "inspect");
    assertTrue("Got error back: " + r, r.isException());
    try {
      throw r.throwException();
    } catch (PolyglotException ex) {
      assertTrue("It is exit exception", ex.isExit());
      assertEquals("Error code is right", 173, ex.getExitStatus());
    }
    assertTrue("It is also an array value", r.hasArrayElements());
    assertEquals("Three elements", 3, r.getArraySize());
    assertFalse("No error at 0", r.getArrayElement(0).isException());
    assertTrue("No error at 1", r.getArrayElement(1).isException());
    assertTrue("No error at 2", r.getArrayElement(2).isException());
    assertEquals("(Error: 2)", r.getArrayElement(1).toString());
    assertEquals("(Error: 2)", r.getArrayElement(2).toString());
    assertEquals("No output printed", "", out.toString());
    assertThat(
        "Stderr contains some errors",
        err.toString(),
        AllOf.allOf(
            containsString("d = Error:2"),
            containsString("t = Error:2"),
            not(containsString("j = 1"))));
  }

  @Test
  public void panicOnUnusedError() throws Exception {
    var code =
        """
        from Standard.Base import all

        inspect =
            j = 1
            d = Error.throw 2
            j
        """;
    var r = ContextUtils.evalModule(ctx, code, "ScriptTest.enso", "inspect");
    assertTrue("Got error back: " + r, r.isException());
    assertEquals("But it is also the right value", 1, r.asInt());
    assertEquals(
        "Compilation warning printed",
        "ScriptTest:5:5: warning: Unused variable d.",
        out.toString().trim());
    assertThat(
        "Stderr contains some errors",
        err.toString(),
        AllOf.allOf(containsString("d = Error:2"), not(containsString("j = 1"))));
  }

  @Test
  public void toTextIsCalledForListings() throws Exception {
    var code =
        """
        from Standard.Base import all

        type My_Warning
            Value msg
            to_text self -> Text = "Beware of "+self.msg

        inspect =
            one = Warning.attach (My_Warning.Value "ONE") 1
            half = Warning.attach (My_Warning.Value "HALF") 2
            two = Warning.attach (My_Warning.Value "TWO") half
            [one, half, two]
        """;
    var r = ContextUtils.evalModule(ctx, code, "ScriptTest.enso", "inspect");
    assertTrue("Got array back: " + r, r.hasArrayElements());
    assertEquals("Got three elements", 3, r.getArraySize());
    assertEquals("One", 1, r.getArrayElement(0).asInt());
    assertEquals("Half", 2, r.getArrayElement(1).asInt());
    assertEquals("Two", 2, r.getArrayElement(2).asInt());
    assertEquals("No output printed", "", out.toString());
    assertThat(
        "Stderr contains some warnings about one\n",
        err.toString(),
        containsString("one = 1\n ! Beware of ONE"));
    assertThat(
        "Stderr contains some warnings about half\n",
        err.toString(),
        containsString("half = 2\n ! Beware of HALF"));
    assertThat(
        "Stderr contains some warnings",
        err.toString(),
        containsString("two = 2\n ! Beware of HALF\n ! Beware of TWO"));
  }
}
