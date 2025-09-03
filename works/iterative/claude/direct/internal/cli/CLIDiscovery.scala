// PURPOSE: Direct-style CLI discovery for finding Claude Code executable
// PURPOSE: Uses direct function calls and Either for error handling without IO wrapper
package works.iterative.claude.direct.internal.cli

import works.iterative.claude.core.{
  CLIError,
  CLINotFoundError,
  NodeJSNotFoundError
}
import works.iterative.claude.direct.Logger

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
    searchInPath(fs, logger)
      .orElse(searchCommonPaths(fs, logger))
      .getOrElse(validateNodeJSPrerequisite(fs, logger))

  /** Search for Claude CLI in system PATH */
  private def searchInPath(
      fs: FileSystemOperations,
      logger: Logger
  ): Option[Either[CLIError, String]] =
    logger.debug("Searching for claude in PATH")
    fs.which("claude").map { path =>
      logger.info(s"Found claude in PATH: $path")
      Right(path)
    }

  /** Search for Claude CLI in common installation paths */
  private def searchCommonPaths(
      fs: FileSystemOperations,
      logger: Logger
  ): Option[Either[CLIError, String]] =
    logger.debug("PATH lookup failed, trying common paths")
    getCommonInstallationPaths()
      .find(isValidExecutable(fs, _))
      .map { path =>
        logger.info(s"Found claude at common path: $path")
        Right(path)
      }

  /** Validate Node.js prerequisite and return appropriate error */
  private def validateNodeJSPrerequisite(
      fs: FileSystemOperations,
      logger: Logger
  ): Either[CLIError, String] =
    logger.debug("Checking for Node.js")
    fs.which("node") match
      case Some(_) =>
        logger.warn(
          "Claude Code CLI not found in PATH or common installation paths"
        )
        Left(
          CLINotFoundError(
            "Claude Code CLI not found in PATH or common installation paths. Please install the Claude Code CLI first."
          )
        )
      case None =>
        logger.error("Node.js not found")
        Left(
          NodeJSNotFoundError(
            "Node.js is required for Claude Code CLI installation. Please install Node.js first."
          )
        )

  /** Get list of common installation paths for Claude CLI */
  private def getCommonInstallationPaths(): List[String] =
    List(
      "/usr/local/bin/claude",
      "/usr/bin/claude",
      "/opt/homebrew/bin/claude",
      System.getProperty("user.home") + "/.local/bin/claude",
      System.getProperty("user.home") + "/bin/claude"
    )

  /** Check if path exists and is executable */
  private def isValidExecutable(
      fs: FileSystemOperations,
      path: String
  ): Boolean =
    fs.exists(path) && fs.isExecutable(path)
