package org.enso.runtimeversionmanager.runner

import com.typesafe.scalalogging.Logger
import org.enso.process.WrappedProcess

import java.nio.file.Path
import scala.sys.process.Process
import scala.util.{Failure, Try}

/** Represents information required to run a system command.
  *
  * @param command the command and its arguments that should be executed
  * @param extraEnv environment variables that should be overridden
  * @param workingDirectory the working directory in which the command should be executed (if None, the working directory is not overridden and is inherited instead)
  */
case class RawCommand(
  command: Seq[String],
  extraEnv: Seq[(String, String)],
  workingDirectory: Option[Path]
) {
  private val logger = Logger[RawCommand]

  /** Runs the command and returns its exit code.
    *
    * May return an exception if it is impossible to run the command (for
    * example due to insufficient permissions or nonexistent executable).
    */
  def run(): Try[Int] =
    wrapError {
      logger.debug("Executing {}", this)
      val processBuilder = builder()
      processBuilder.inheritIO()
      val process = processBuilder.start()
      process.waitFor()
    }

  /** Runs the command and returns its exit code along with any output produced..
    *
    * May return an exception if it is impossible to run the command (for
    * example due to insufficient permissions or nonexistent executable).
    *
    * @param timeoutInSeconds timeout specifying how long this thread will wait
    *                         for the process to finish until it attempts to kill it
    */
  def runAndCaptureOutput(
    timeoutInSeconds: Option[Long] = Some(300)
  ): Try[(Int, String)] =
    wrapError {
      logger.debug("Executing {}", this)
      val processBuilder = builder()
      val process        = processBuilder.start()
      val wrappedProcess = new WrappedProcess(command, process)
      val stringBuilder  = new StringBuilder()
      wrappedProcess.registerStringBuilderAppendTarget(stringBuilder)
      val result = wrappedProcess.join(
        waitForDescendants = true,
        timeoutInSeconds.getOrElse(Long.MaxValue)
      )
      (result.exitCode, stringBuilder.toString())
    }

  /** Runs the command and returns its standard output as [[String]].
    *
    * The standard error is printed to the console.
    *
    * May return an exception if it is impossible to run the command or the
    * command returned non-zero exit code.
    */
  def captureOutput(): Try[String] =
    wrapError {
      logger.debug("Executing {}", this)
      val processBuilder = Process(command, None, extraEnv: _*)
      processBuilder.!!
    }

  /** Returns a [[ProcessBuilder]] that can be used to start the process.
    *
    * This is an advanced feature and it has to be used very carefully - the
    * builder and the constructed process (as long as it is running) must not
    * leak outside of the enclosing `withCommand` function to preserve the
    * guarantees that the environment the process requires still exists.
    */
  def builder(): ProcessBuilder = {
    val processBuilder = new java.lang.ProcessBuilder(command: _*)
    for ((key, value) <- extraEnv) {
      processBuilder.environment().put(key, value)
    }
    workingDirectory.foreach(path => processBuilder.directory(path.toFile))
    processBuilder
  }

  /** Runs the provided action and wraps any errors into a [[Failure]]
    * containing a [[RunnerError]].
    */
  private def wrapError[R](action: => R): Try[R] =
    Try(action).recoverWith(error =>
      Failure(
        RunnerError(
          s"Could not run the command $toString due to: $error",
          error
        )
      )
    )

  /** A textual representation of the command in a format that can be copied in
    * to a terminal and executed.
    */
  override def toString: String = {
    def escapeQuotes(string: String): String =
      "\"" + string.replace("\"", "\\\"") + "\""
    val environmentDescription =
      extraEnv.map(v => s"${v._1}=${escapeQuotes(v._2)} ").mkString
    val commandDescription = command.map(escapeQuotes).mkString(" ")
    environmentDescription + commandDescription
  }
}
