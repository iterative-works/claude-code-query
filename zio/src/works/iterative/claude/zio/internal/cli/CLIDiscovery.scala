// PURPOSE: CLI discovery functionality for finding the Claude Code executable
// PURPOSE: Uses functional programming with dependency injection for testability

package works.iterative.claude.zio.internal.cli

import zio.*
import works.iterative.claude.core.{
  CLIError,
  CLINotFoundError,
  NodeJSNotFoundError
}

object CLIDiscovery:

  /** Find the Claude Code CLI executable using PATH lookup and common
    * installation paths, failing with a typed [[CLIError]] when not found.
    */
  def findClaude: IO[CLIError, String] = findClaude(RealFileSystemOps)

  /** Internal implementation with injected file system operations for testing.
    */
  private[claude] def findClaude(fs: FileSystemOps): IO[CLIError, String] =
    for
      _ <- ZIO.logDebug("Searching for claude in PATH")
      result <- fs.which("claude").flatMap {
        case Some(path) =>
          ZIO.logInfo(s"Found claude in PATH: $path").as(path)
        case None =>
          ZIO.logDebug("Claude not found in PATH, trying common paths")
            *> tryCommonPaths(fs)
      }
    yield result

  /** Try common installation paths when PATH lookup fails. */
  private def tryCommonPaths(fs: FileSystemOps): IO[CLIError, String] =
    val homeDir = sys.env.getOrElse("HOME", "")
    val commonPaths = List(
      s"$homeDir/.npm-global/bin/claude",
      "/usr/local/bin/claude",
      s"$homeDir/.local/bin/claude",
      s"$homeDir/node_modules/.bin/claude",
      s"$homeDir/.yarn/bin/claude"
    )
    ZIO.logDebug(s"Trying common paths: ${commonPaths.mkString(", ")}")
      *> findFirstExecutable(fs, commonPaths)

  /** Find the first executable file from a list of paths. */
  private def findFirstExecutable(
      fs: FileSystemOps,
      paths: List[String]
  ): IO[CLIError, String] =
    paths match
      case Nil          => checkNodeAndFail(fs)
      case path :: rest =>
        fs.exists(path).flatMap {
          case false => findFirstExecutable(fs, rest)
          case true  =>
            fs.isExecutable(path).flatMap {
              case true =>
                ZIO.logInfo(s"Found claude executable at: $path").as(path)
              case false => findFirstExecutable(fs, rest)
            }
        }

  /** Check if Node.js is available and fail with the appropriate error. */
  private def checkNodeAndFail(fs: FileSystemOps): IO[CLIError, String] =
    ZIO.logWarning("Claude CLI not found in any common paths")
      *> fs.which("node").flatMap {
        case Some(_) =>
          ZIO.logWarning("Node.js found but Claude CLI not installed")
            *> ZIO.fail(
              CLINotFoundError(
                "Claude Code CLI not found. Please install it with: npm install -g claude-code\n" +
                  "Alternative installation methods:\n" +
                  "  • Using yarn: yarn global add claude-code\n" +
                  "  • Using pnpm: pnpm add -g claude-code\n" +
                  "Verify installation: claude --version"
              )
            )
        case None =>
          ZIO.logError("Node.js not found - prerequisite for Claude CLI")
            *> ZIO.fail(
              NodeJSNotFoundError(
                "Node.js not found. Please install Node.js first, then install Claude Code with: npm install -g claude-code\n" +
                  "Install Node.js from: https://nodejs.org/\n" +
                  "Or use a package manager:\n" +
                  "  • macOS: brew install node\n" +
                  "  • Ubuntu/Debian: sudo apt install nodejs npm\n" +
                  "  • Windows: winget install OpenJS.NodeJS"
              )
            )
      }
