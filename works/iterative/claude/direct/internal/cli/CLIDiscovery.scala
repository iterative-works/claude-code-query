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
        // Try common installation paths as fallback
        logger.debug("PATH lookup failed, trying common paths")
        tryCommonPaths(fs, logger)

  /** Try common installation paths for Claude CLI */
  private def tryCommonPaths(
      fs: FileSystemOperations,
      logger: Logger
  ): Either[CLIError, String] =
    val commonPaths = List(
      "/usr/local/bin/claude",
      "/usr/bin/claude",
      "/opt/homebrew/bin/claude",
      System.getProperty("user.home") + "/.local/bin/claude",
      System.getProperty("user.home") + "/bin/claude"
    )

    commonPaths.find(path => fs.exists(path) && fs.isExecutable(path)) match
      case Some(path) =>
        logger.info(s"Found claude at common path: $path")
        Right(path)
      case None =>
        // Check if Node.js is available before returning CLINotFoundError
        checkNodeJSPrerequisite(fs, logger)

  /** Check if Node.js is available as a prerequisite for Claude CLI */
  private def checkNodeJSPrerequisite(
      fs: FileSystemOperations,
      logger: Logger
  ): Either[CLIError, String] =
    logger.debug("Checking for Node.js")
    fs.which("node") match
      case Some(_) =>
        // Node.js is available, return CLINotFoundError
        logger.warn(
          "Claude Code CLI not found in PATH or common installation paths"
        )
        Left(
          CLINotFoundError(
            "Claude Code CLI not found in PATH or common installation paths. Please install the Claude Code CLI first."
          )
        )
      case None =>
        // Node.js is missing, return NodeJSNotFoundError
        logger.error("Node.js not found")
        Left(
          NodeJSNotFoundError(
            "Node.js is required for Claude Code CLI installation. Please install Node.js first."
          )
        )
