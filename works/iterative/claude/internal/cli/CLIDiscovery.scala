package works.iterative.claude.internal.cli

// PURPOSE: CLI discovery functionality for finding Claude Code executable
// PURPOSE: Uses functional programming with dependency injection for testability

import cats.effect.IO
import org.typelevel.log4cats.Logger

object CLIDiscovery:

  /** Find Claude Code CLI executable using PATH lookup and common installation
    * paths
    */
  def findClaude(logger: Logger[IO]): IO[Either[CLIError, String]] =
    findClaude(RealFileSystemOps, logger)

  /** Internal implementation with injected file system operations for testing
    */
  private[claude] def findClaude(
      fs: FileSystemOps,
      logger: Logger[IO]
  ): IO[Either[CLIError, String]] =
    for
      // First try PATH lookup
      _ <- logger.debug("Searching for claude in PATH")
      pathResult <- fs.which("claude")
      result <- pathResult match
        case Some(path) =>
          for
            _ <- logger.info(s"Found claude in PATH: $path")
            result <- IO.pure(Right(path))
          yield result
        case None =>
          for
            _ <- logger.debug("Claude not found in PATH, trying common paths")
            result <- tryCommonPaths(fs, logger)
          yield result
    yield result

  /** Try common installation paths when PATH lookup fails */
  private def tryCommonPaths(
      fs: FileSystemOps,
      logger: Logger[IO]
  ): IO[Either[CLIError, String]] =
    val homeDir = sys.env.get("HOME").getOrElse("")
    val commonPaths = List(
      s"$homeDir/.npm-global/bin/claude",
      "/usr/local/bin/claude",
      s"$homeDir/.local/bin/claude",
      s"$homeDir/node_modules/.bin/claude",
      s"$homeDir/.yarn/bin/claude"
    )

    for
      _ <- logger.debug(s"Trying common paths: ${commonPaths.mkString(", ")}")
      result <- findFirstExecutable(fs, commonPaths, logger)
    yield result

  /** Find first executable file from a list of paths */
  private def findFirstExecutable(
      fs: FileSystemOps,
      paths: List[String],
      logger: Logger[IO]
  ): IO[Either[CLIError, String]] =
    paths match
      case Nil          => checkNodeAndReturnError(fs, logger)
      case path :: rest =>
        fs.exists(path)
          .flatMap: exists =>
            if exists then
              fs.isExecutable(path)
                .flatMap: executable =>
                  if executable then
                    for
                      _ <- logger.info(s"Found claude executable at: $path")
                      result <- IO.pure(Right(path))
                    yield result
                  else findFirstExecutable(fs, rest, logger)
            else findFirstExecutable(fs, rest, logger)

  /** Check if Node.js is available and return appropriate error */
  private def checkNodeAndReturnError(
      fs: FileSystemOps,
      logger: Logger[IO]
  ): IO[Either[CLIError, String]] =
    for
      _ <- logger.warn("Claude CLI not found in any common paths")
      nodeResult <- fs.which("node")
      result <- nodeResult match
        case Some(_) =>
          for
            _ <- logger.warn("Node.js found but Claude CLI not installed")
            result <- IO.pure(
              Left(
                CLINotFoundError(
                  "Claude Code CLI not found. Please install it with: npm install -g claude-code\n" +
                    "Alternative installation methods:\n" +
                    "  • Using yarn: yarn global add claude-code\n" +
                    "  • Using pnpm: pnpm add -g claude-code\n" +
                    "Verify installation: claude --version"
                )
              )
            )
          yield result
        case None =>
          for
            _ <- logger.error("Node.js not found - prerequisite for Claude CLI")
            result <- IO.pure(
              Left(
                NodeJSNotFoundError(
                  "Node.js not found. Please install Node.js first, then install Claude Code with: npm install -g claude-code\n" +
                    "Install Node.js from: https://nodejs.org/\n" +
                    "Or use a package manager:\n" +
                    "  • macOS: brew install node\n" +
                    "  • Ubuntu/Debian: sudo apt install nodejs npm\n" +
                    "  • Windows: winget install OpenJS.NodeJS"
                )
              )
            )
          yield result
    yield result
