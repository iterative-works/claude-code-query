// PURPOSE: Direct-style CLI discovery for finding Claude Code executable
// PURPOSE: Uses direct function calls and Either for error handling without IO wrapper
package works.iterative.claude.direct.internal.cli

import works.iterative.claude.core.{
  CLIError,
  CLINotFoundError,
  NodeJSNotFoundError
}

trait Logger:
  def debug(msg: String): Unit
  def info(msg: String): Unit
  def warn(msg: String): Unit
  def error(msg: String): Unit

trait FileSystemOperations:
  def which(command: String): Option[String]
  def exists(path: String): Boolean
  def isExecutable(path: String): Boolean

object RealFileSystemOps extends FileSystemOperations:
  def which(command: String): Option[String] = FileSystemOps.which(command)
  def exists(path: String): Boolean = FileSystemOps.exists(path)
  def isExecutable(path: String): Boolean = FileSystemOps.isExecutable(path)

object CLIDiscovery:

  /** Find Claude Code CLI executable using PATH lookup and common installation
    * paths
    */
  def findClaude(using logger: Logger): Either[CLIError, String] =
    findClaude(RealFileSystemOps, logger)

  /** Internal implementation with injected file system operations for testing
    */
  private[claude] def findClaude(
      fs: FileSystemOperations,
      logger: Logger
  ): Either[CLIError, String] =
    // First try PATH lookup
    logger.debug("Searching for claude in PATH")
    fs.which("claude") match
      case Some(path) =>
        logger.info(s"Found claude in PATH: $path")
        Right(path)
      case None =>
        // For now, just implement the PATH case to make T2.1 pass
        // Will implement fallback paths in next tests
        Left(CLINotFoundError("Claude not found in PATH"))
