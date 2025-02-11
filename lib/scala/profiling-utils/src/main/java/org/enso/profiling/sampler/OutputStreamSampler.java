package org.enso.profiling.sampler;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import org.netbeans.modules.sampler.Sampler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gathers application performance statistics that can be visualised in Java VisualVM, and writes it
 * to the provided output.
 */
public final class OutputStreamSampler implements MethodsSampler {

  private final Sampler sampler = Sampler.createSampler(this.getClass().getSimpleName());
  private final OutputStream outputStream;

  private boolean isSamplingStarted = false;

  private static Logger logger = LoggerFactory.getLogger(OutputStreamSampler.class);

  /**
   * Creates the {@link OutputStreamSampler} for provided output stream.
   *
   * @param outputStream the output stream to write result to.
   */
  public OutputStreamSampler(OutputStream outputStream) {
    this.outputStream = outputStream;
  }

  public static OutputStreamSampler ofFile(File file) throws FileNotFoundException {
    return new OutputStreamSampler(new FileOutputStream(file));
  }

  @Override
  public void start() {
    synchronized (this) {
      if (sampler != null && !isSamplingStarted) {
        logger.trace("Starting profiling sampler");
        sampler.start();
        isSamplingStarted = true;
      }
    }
  }

  @Override
  public void stop() throws IOException {
    synchronized (this) {
      if (isSamplingStarted) {
        logger.trace("Stopping profiling sampler");
        try (DataOutputStream dos = new DataOutputStream(outputStream)) {
          sampler.stopAndWriteTo(dos);
        }
        isSamplingStarted = false;
      }
    }
  }
}
