package org.enso.interpreter.node.expression.builtin.immutable;

import com.oracle.truffle.api.nodes.Node;
import org.enso.interpreter.dsl.AcceptsWarning;
import org.enso.interpreter.dsl.BuiltinMethod;
import org.enso.interpreter.runtime.data.vector.ArrayLikeHelpers;
import org.enso.interpreter.runtime.data.vector.ArrayLikeLengthNode;

@BuiltinMethod(
    type = "Array_Like_Helpers",
    name = "slice",
    description = "Returns a slice of this Vector.")
public final class SliceArrayVectorNode extends Node {
  private @Child ArrayLikeLengthNode lengthNode = ArrayLikeLengthNode.create();

  private SliceArrayVectorNode() {}

  static SliceArrayVectorNode build() {
    return new SliceArrayVectorNode();
  }

  Object execute(@AcceptsWarning Object vector, long start, long end) {
    var len = lengthNode.executeLength(vector);
    return ArrayLikeHelpers.slice(vector, start, end, len);
  }
}
