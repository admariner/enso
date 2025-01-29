package org.enso.interpreter.node.expression.builtin.text;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.nodes.Node;
import org.enso.interpreter.dsl.BuiltinMethod;
import org.enso.interpreter.runtime.EnsoContext;
import org.enso.interpreter.runtime.data.EnsoMultiValue;
import org.enso.interpreter.runtime.data.atom.Atom;
import org.enso.interpreter.runtime.data.atom.AtomConstructor;
import org.enso.interpreter.runtime.data.atom.StructsLibrary;
import org.enso.interpreter.runtime.data.text.Text;

@BuiltinMethod(type = "Any", name = "to_text", description = "Generic text conversion.")
@GenerateUncached
public abstract class AnyToTextNode extends Node {

  public static AnyToTextNode build() {
    return AnyToTextNodeGen.create();
  }

  public abstract Text execute(Object self);

  @Specialization
  Text doAtom(Atom at) {
    if (at.getConstructor().getArity() == 0) {
      return consName(at.getConstructor());
    } else {
      return doComplexAtom(at);
    }
  }

  @Specialization
  Text doMultiValue(
      EnsoMultiValue mv,
      @Cached EnsoMultiValue.CastToNode castToNode,
      @Cached AnyToTextNode toTextNode) {
    var ctx = EnsoContext.get(this);
    var any = ctx.getBuiltins().any();
    var first = castToNode.findTypeOrNull(any, mv, false, false);
    assert first != null;
    return toTextNode.execute(first);
  }

  @Fallback
  Text doOther(Object object) {
    try {
      return Text.create(showObject(object));
    } catch (UnsupportedMessageException e) {
      CompilerDirectives.transferToInterpreter();
      return Text.create(object.toString());
    }
  }

  @CompilerDirectives.TruffleBoundary
  private Text consName(AtomConstructor constructor) {
    return Text.create(constructor.getDisplayName());
  }

  @CompilerDirectives.TruffleBoundary
  private Text doComplexAtom(Atom atom) {
    var interop = InteropLibrary.getUncached();
    var structs = StructsLibrary.getUncached();
    Text res = Text.create("(", consName(atom.getConstructor()));
    res = Text.create(res, " ");
    try {
      res = Text.create(res, showObject(structs.getField(atom, 0)));
    } catch (UnsupportedMessageException e) {
      res = Text.create(res, structs.getField(atom, 0).toString());
    }
    for (int i = 1; i < atom.getConstructor().getArity(); i++) {
      res = Text.create(res, " ");
      try {
        res = Text.create(res, showObject(structs.getField(atom, i)));
      } catch (UnsupportedMessageException e) {
        res = Text.create(res, structs.getField(atom, i).toString());
      }
    }
    res = Text.create(res, ")");
    return res;
  }

  @CompilerDirectives.TruffleBoundary
  private String showObject(Object child) throws UnsupportedMessageException {
    if (child == null) {
      // TODO [RW] This is a temporary workaround to make it possible to display errors related to
      // https://www.pivotaltracker.com/story/show/181652974
      // Most likely it should be removed once that is implemented.
      return "null";
    } else if (child instanceof Boolean) {
      return (boolean) child ? "True" : "False";
    } else {
      var interop = InteropLibrary.getUncached();
      return interop.asString(interop.toDisplayString(child));
    }
  }
}
