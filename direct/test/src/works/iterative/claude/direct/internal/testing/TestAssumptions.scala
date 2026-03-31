// PURPOSE: Provides test assumptions for environment requirements and command availability
// PURPOSE: Allows tests to skip gracefully when dependencies are not met

package works.iterative.claude.direct.internal.testing

import scala.util.Try
import java.io.File

object TestAssumptions:

  /** Check if we're running on a Unix-like system */
  def assumeUnixSystem(): Unit =
    val osName = System.getProperty("os.name").toLowerCase
    assume(
      !osName.contains("windows"),
      s"Test requires Unix-like system, but running on: $osName"
    )

  /** Check if a command is available in PATH */
  def assumeCommand(command: String): Unit =
    val available = Try {
      val process = new ProcessBuilder("which", command).start()
      process.waitFor() == 0
    }.getOrElse(false)

    assume(available, s"Command '$command' not found in PATH")

  /** Check if a file path exists */
  def assumeFileExists(path: String): Unit =
    assume(
      new File(path).exists(),
      s"Required file not found: $path"
    )

  /** Check multiple commands are available */
  def assumeCommands(commands: String*): Unit =
    commands.foreach(assumeCommand)

  /** Combined check for Unix system with required commands */
  def assumeUnixWithCommands(commands: String*): Unit =
    assumeUnixSystem()
    assumeCommands(commands*)
