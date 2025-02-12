package org.enso.interpreter.runtime.data.vector;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.nodes.Node;
import org.enso.interpreter.node.expression.foreign.HostValueToEnsoNode;
import org.enso.interpreter.runtime.EnsoContext;
import org.enso.interpreter.runtime.data.EnsoObject;
import org.enso.interpreter.runtime.data.Type;
import org.enso.interpreter.runtime.data.hash.EnsoHashMap;
import org.enso.interpreter.runtime.library.dispatch.TypesLibrary;
import org.enso.interpreter.runtime.warning.AppendWarningNode;
import org.enso.interpreter.runtime.warning.WarningsLibrary;

@ExportLibrary(TypesLibrary.class)
@ExportLibrary(InteropLibrary.class)
@ExportLibrary(WarningsLibrary.class)
final class ArraySlice extends EnsoObject {
  private final Object storage;
  private final long start;
  private final long end;

  private ArraySlice(Object base, long start, long end) {
    var s = findStorage(base);
    if (s instanceof ArraySlice slice) {
      this.storage = slice.storage;
      this.start = slice.start + start;
      this.end = Math.min(slice.end, slice.start + end);
    } else {
      if (CompilerDirectives.inInterpreter()) {
        if (!InteropLibrary.getUncached().hasArrayElements(s)) {
          throw EnsoContext.get(null)
              .raiseAssertionPanic(
                  null, "ArraySlice needs array-like delegate, but got: " + s, null);
        }
      }
      this.storage = s;
      this.start = start;
      this.end = end;
    }
  }

  private static Object findStorage(Object storage) {
    return switch (storage) {
      case Vector.Generic gen -> gen.toArray();
      case ArraySlice slice -> slice;
      default -> storage;
    };
  }

  static Vector createOrNull(Object storage, long start, long this_length, long end) {
    long slice_start = Math.max(0, start);
    long slice_end = Math.min(this_length, end);
    Object slice;
    if (slice_start >= slice_end) {
      slice = ArrayLikeHelpers.allocate(0);
    } else if ((slice_start == 0) && (slice_end == this_length)) {
      return null;
    } else {
      slice = new ArraySlice(storage, slice_start, slice_end);
    }
    return Vector.fromInteropArray(slice);
  }

  /**
   * Marks the object as array-like for Polyglot APIs.
   *
   * @return {@code true}
   */
  @ExportMessage
  public boolean hasArrayElements() {
    return true;
  }

  @ExportMessage
  public long getArraySize(@Shared("interop") @CachedLibrary(limit = "3") InteropLibrary interop)
      throws UnsupportedMessageException {
    long storageSize = interop.getArraySize(storage);
    return Math.max(0, Math.min(storageSize, end) - start);
  }

  /**
   * Handles reading an element by index through the polyglot API.
   *
   * @param index the index to read
   * @return the element value at the provided index
   * @throws InvalidArrayIndexException when the index is out of bounds.
   */
  @ExportMessage
  public Object readArrayElement(
      long index,
      @Shared("interop") @CachedLibrary(limit = "3") InteropLibrary interop,
      @CachedLibrary(limit = "3") WarningsLibrary warnings,
      @Cached HostValueToEnsoNode toEnso,
      @Cached AppendWarningNode appendWarningNode)
      throws InvalidArrayIndexException, UnsupportedMessageException {
    if (index < 0 || index >= getArraySize(interop)) {
      throw InvalidArrayIndexException.create(index);
    }

    var v = interop.readArrayElement(storage, start + index);
    if (this.hasWarnings(warnings)) {
      var extracted = this.getWarnings(false, warnings);
      if (warnings.hasWarnings(v)) {
        v = warnings.removeWarnings(v);
      }
      return appendWarningNode.executeAppend(null, toEnso.execute(v), extracted);
    }
    return toEnso.execute(v);
  }

  /**
   * Exposes an index validity check through the polyglot API.
   *
   * @param index the index to check
   * @return {@code true} if the index is valid, {@code false} otherwise.
   */
  @ExportMessage
  boolean isArrayElementReadable(
      long index, @Shared("interop") @CachedLibrary(limit = "3") InteropLibrary interop) {
    try {
      return index >= 0 && index < getArraySize(interop);
    } catch (UnsupportedMessageException e) {
      return false;
    }
  }

  @ExportMessage
  boolean isArrayElementModifiable(long index) {
    return false;
  }

  @ExportMessage
  final void writeArrayElement(long index, Object value) throws UnsupportedMessageException {
    throw UnsupportedMessageException.create();
  }

  @ExportMessage
  boolean isArrayElementInsertable(long index) {
    return false;
  }

  @ExportMessage
  boolean isArrayElementRemovable(long index) {
    return false;
  }

  @ExportMessage
  final void removeArrayElement(long index) throws UnsupportedMessageException {
    throw UnsupportedMessageException.create();
  }

  @ExportMessage
  boolean hasWarnings(@Shared("warnsLib") @CachedLibrary(limit = "3") WarningsLibrary warnings) {
    return warnings.hasWarnings(this.storage);
  }

  @ExportMessage
  EnsoHashMap getWarnings(
      boolean shouldWrap, @Shared("warnsLib") @CachedLibrary(limit = "3") WarningsLibrary warnings)
      throws UnsupportedMessageException {
    return warnings.getWarnings(this.storage, shouldWrap);
  }

  @ExportMessage
  Object removeWarnings(@Shared("warnsLib") @CachedLibrary(limit = "3") WarningsLibrary warnings)
      throws UnsupportedMessageException {
    Object newStorage = warnings.removeWarnings(this.storage);
    return new ArraySlice(newStorage, start, end);
  }

  @ExportMessage
  boolean isLimitReached(@Shared("warnsLib") @CachedLibrary(limit = "3") WarningsLibrary warnings) {
    return warnings.isLimitReached(this.storage);
  }

  @ExportMessage
  boolean hasType() {
    return true;
  }

  @ExportMessage
  Type getType(@Bind("$node") Node node) {
    var ctx = EnsoContext.get(node);
    return ctx.getBuiltins().array();
  }

  @Override
  @ExportMessage
  @TruffleBoundary
  public Object toDisplayString(boolean allowSideEffects) {
    return "ArraySlice{" + start + ", " + end + "}";
  }
}
