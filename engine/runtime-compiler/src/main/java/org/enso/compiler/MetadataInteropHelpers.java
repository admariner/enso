package org.enso.compiler;

import org.enso.compiler.core.IR;
import org.enso.compiler.core.ir.MetadataStorage.MetadataPair;
import org.enso.compiler.core.ir.ProcessingPass;
import org.enso.compiler.pass.IRProcessingPass;
import scala.Option;

/**
 * A set of helper methods for handling of IR metadata.
 *
 * <p>This encapsulates the friction of interop between Scala and Java types.
 */
public final class MetadataInteropHelpers {
  public static <T> T getMetadataOrNull(IR ir, IRProcessingPass pass, Class<T> expectedType) {
    Option<ProcessingPass.Metadata> option = ir.passData().get(pass);
    if (option.isDefined()) {
      try {
        return expectedType.cast(option.get());
      } catch (ClassCastException exception) {
        throw new IllegalStateException(
            "Unexpected metadata type "
                + option.get().getClass().getCanonicalName()
                + " "
                + "for "
                + pass,
            exception);
      }
    } else {
      return null;
    }
  }

  public static <T> T getMetadata(IR ir, IRProcessingPass pass, Class<T> expectedType) {
    T metadataOrNull = getMetadataOrNull(ir, pass, expectedType);
    if (metadataOrNull == null) {
      throw new IllegalStateException("Missing expected " + pass + " metadata for " + ir + ".");
    }

    return metadataOrNull;
  }

  public static <T extends ProcessingPass> void updateMetadata(
      IR ir, T pass, ProcessingPass.Metadata metadata) {
    var metaPair = new MetadataPair<>(pass, metadata);
    ir.passData().update(metaPair);
  }

  private MetadataInteropHelpers() {}
}
