package works.iterative.claude.effectful.internal.cli

import cats.effect.IO
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
// CLIDiscovery and FileSystemOps are now in the same package
import works.iterative.claude.core.{
  CLINotFoundError,
  NodeJSNotFoundError,
  ProcessError
}

class CLIDiscoveryTest extends CatsEffectSuite:

  test("findClaude succeeds when claude is found in PATH"):
    val mockLogger = new Logger[IO]:
      def debug(message: => String): IO[Unit] = IO.unit
      def debug(t: Throwable)(message: => String): IO[Unit] = IO.unit
      def info(message: => String): IO[Unit] = IO.unit
      def info(t: Throwable)(message: => String): IO[Unit] = IO.unit
      def warn(message: => String): IO[Unit] = IO.unit
      def warn(t: Throwable)(message: => String): IO[Unit] = IO.unit
      def error(message: => String): IO[Unit] = IO.unit
      def error(t: Throwable)(message: => String): IO[Unit] = IO.unit
      def trace(message: => String): IO[Unit] = IO.unit
      def trace(t: Throwable)(message: => String): IO[Unit] = IO.unit

    val mockFs = new FileSystemOps:
      def which(command: String): IO[Option[String]] =
        if command == "claude" then IO.pure(Some("/usr/local/bin/claude"))
        else IO.pure(None)
      def exists(path: String): IO[Boolean] = IO.pure(true)
      def isExecutable(path: String): IO[Boolean] = IO.pure(true)

    CLIDiscovery
      .findClaude(mockFs, mockLogger)
      .map: result =>
        assert(result.isRight)
        assertEquals(result.getOrElse(""), "/usr/local/bin/claude")

  test("findClaude fails with CLINotFoundError when claude not found anywhere"):
    val mockLogger = new Logger[IO]:
      def debug(message: => String): IO[Unit] = IO.unit
      def debug(t: Throwable)(message: => String): IO[Unit] = IO.unit
      def info(message: => String): IO[Unit] = IO.unit
      def info(t: Throwable)(message: => String): IO[Unit] = IO.unit
      def warn(message: => String): IO[Unit] = IO.unit
      def warn(t: Throwable)(message: => String): IO[Unit] = IO.unit
      def error(message: => String): IO[Unit] = IO.unit
      def error(t: Throwable)(message: => String): IO[Unit] = IO.unit
      def trace(message: => String): IO[Unit] = IO.unit
      def trace(t: Throwable)(message: => String): IO[Unit] = IO.unit

    val mockFs = new FileSystemOps:
      def which(command: String): IO[Option[String]] =
        command match
          case "claude" => IO.pure(None) // Claude not found
          case "node" => IO.pure(Some("/usr/bin/node")) // Node.js is available
          case _      => IO.pure(None)
      def exists(path: String): IO[Boolean] = IO.pure(false)
      def isExecutable(path: String): IO[Boolean] = IO.pure(false)

    CLIDiscovery
      .findClaude(mockFs, mockLogger)
      .map: result =>
        assert(result.isLeft)
        result.left.foreach: error =>
          assert(error.isInstanceOf[CLINotFoundError])
          assert(error.message.contains("Claude Code CLI not found"))

  test("findClaude falls back to common paths when PATH lookup fails"):
    val mockLogger = new Logger[IO]:
      def debug(message: => String): IO[Unit] = IO.unit
      def debug(t: Throwable)(message: => String): IO[Unit] = IO.unit
      def info(message: => String): IO[Unit] = IO.unit
      def info(t: Throwable)(message: => String): IO[Unit] = IO.unit
      def warn(message: => String): IO[Unit] = IO.unit
      def warn(t: Throwable)(message: => String): IO[Unit] = IO.unit
      def error(message: => String): IO[Unit] = IO.unit
      def error(t: Throwable)(message: => String): IO[Unit] = IO.unit
      def trace(message: => String): IO[Unit] = IO.unit
      def trace(t: Throwable)(message: => String): IO[Unit] = IO.unit

    val mockFs = new FileSystemOps:
      def which(command: String): IO[Option[String]] =
        IO.pure(None) // PATH lookup fails
      def exists(path: String): IO[Boolean] =
        // Simulate finding claude in ~/.npm-global/bin/claude
        IO.pure(path.endsWith("/.npm-global/bin/claude"))
      def isExecutable(path: String): IO[Boolean] =
        IO.pure(path.endsWith("/.npm-global/bin/claude"))

    CLIDiscovery
      .findClaude(mockFs, mockLogger)
      .map: result =>
        assert(result.isRight)
        assert(result.getOrElse("").endsWith("/.npm-global/bin/claude"))

  test("findClaude returns NodeJSNotFoundError when Node.js is missing"):
    val mockLogger = new Logger[IO]:
      def debug(message: => String): IO[Unit] = IO.unit
      def debug(t: Throwable)(message: => String): IO[Unit] = IO.unit
      def info(message: => String): IO[Unit] = IO.unit
      def info(t: Throwable)(message: => String): IO[Unit] = IO.unit
      def warn(message: => String): IO[Unit] = IO.unit
      def warn(t: Throwable)(message: => String): IO[Unit] = IO.unit
      def error(message: => String): IO[Unit] = IO.unit
      def error(t: Throwable)(message: => String): IO[Unit] = IO.unit
      def trace(message: => String): IO[Unit] = IO.unit
      def trace(t: Throwable)(message: => String): IO[Unit] = IO.unit

    val mockFs = new FileSystemOps:
      def which(command: String): IO[Option[String]] =
        command match
          case "claude" => IO.pure(None) // Claude not in PATH
          case "node"   => IO.pure(None) // Node.js not found
          case _        => IO.pure(None)
      def exists(path: String): IO[Boolean] =
        IO.pure(false) // No claude in common paths
      def isExecutable(path: String): IO[Boolean] = IO.pure(false)

    CLIDiscovery
      .findClaude(mockFs, mockLogger)
      .map: result =>
        assert(result.isLeft)
        result.left.foreach: error =>
          assert(error.isInstanceOf[NodeJSNotFoundError])
          assert(error.message.contains("Node.js"))

  test("CLINotFoundError contains actionable installation guidance"):
    val error = CLINotFoundError(
      "Claude Code CLI not found. Please install it with: npm install -g claude-code"
    )
    assert(error.message.contains("Claude Code CLI not found"))
    assert(error.message.contains("npm install -g claude-code"))
    assertEquals(error.getMessage, error.message) // Verify Throwable interface

  test("NodeJSNotFoundError provides Node.js installation guidance"):
    val error = NodeJSNotFoundError(
      "Node.js not found. Please install Node.js first, then install Claude Code with: npm install -g claude-code"
    )
    assert(error.message.contains("Node.js not found"))
    assert(error.message.contains("install Node.js first"))
    assert(error.message.contains("npm install -g claude-code"))
    assertEquals(error.getMessage, error.message) // Verify Throwable interface

  test("ProcessError includes exit code and actionable message"):
    val error = ProcessError("Command 'claude --version' failed", 127)
    assert(error.message.contains("Command 'claude --version' failed"))
    assertEquals(error.exitCode, 127)
    assertEquals(error.getMessage, error.message) // Verify Throwable interface

  test("public findClaude method uses default filesystem"):
    // This test verifies the public API works with default implementation
    // It will either find claude or return CLINotFoundError (both are success cases)
    val mockLogger = new Logger[IO]:
      def debug(message: => String): IO[Unit] = IO.unit
      def debug(t: Throwable)(message: => String): IO[Unit] = IO.unit
      def info(message: => String): IO[Unit] = IO.unit
      def info(t: Throwable)(message: => String): IO[Unit] = IO.unit
      def warn(message: => String): IO[Unit] = IO.unit
      def warn(t: Throwable)(message: => String): IO[Unit] = IO.unit
      def error(message: => String): IO[Unit] = IO.unit
      def error(t: Throwable)(message: => String): IO[Unit] = IO.unit
      def trace(message: => String): IO[Unit] = IO.unit
      def trace(t: Throwable)(message: => String): IO[Unit] = IO.unit

    CLIDiscovery
      .findClaude(mockLogger)
      .map: result =>
        // Should return either success (if claude is installed) or CLINotFoundError (expected failure)
        result match
          case Right(_)                  => () // Claude found - success
          case Left(_: CLINotFoundError) =>
            () // Claude not found - expected failure
          case Left(other) => fail(s"Unexpected error type: $other")

  test("findClaude logs PATH search attempt and success"):
    var debugMessages = List.empty[String]
    var infoMessages = List.empty[String]

    val mockLogger = new Logger[IO]:
      def debug(message: => String): IO[Unit] = IO {
        debugMessages = message :: debugMessages
      }
      def debug(t: Throwable)(message: => String): IO[Unit] = IO.unit
      def info(message: => String): IO[Unit] = IO {
        infoMessages = message :: infoMessages
      }
      def info(t: Throwable)(message: => String): IO[Unit] = IO.unit
      def warn(message: => String): IO[Unit] = IO.unit
      def warn(t: Throwable)(message: => String): IO[Unit] = IO.unit
      def error(message: => String): IO[Unit] = IO.unit
      def error(t: Throwable)(message: => String): IO[Unit] = IO.unit
      def trace(message: => String): IO[Unit] = IO.unit
      def trace(t: Throwable)(message: => String): IO[Unit] = IO.unit

    val mockFs = new FileSystemOps:
      def which(command: String): IO[Option[String]] =
        if command == "claude" then IO.pure(Some("/usr/local/bin/claude"))
        else IO.pure(None)
      def exists(path: String): IO[Boolean] = IO.pure(true)
      def isExecutable(path: String): IO[Boolean] = IO.pure(true)

    CLIDiscovery
      .findClaude(mockFs, mockLogger)
      .map: result =>
        assert(result.isRight)
        assert(debugMessages.contains("Searching for claude in PATH"))
        assert(
          infoMessages.contains("Found claude in PATH: /usr/local/bin/claude")
        )

  test("findClaude logs common paths search when PATH fails"):
    var debugMessages = List.empty[String]
    var infoMessages = List.empty[String]

    val mockLogger = new Logger[IO]:
      def debug(message: => String): IO[Unit] = IO {
        debugMessages = message :: debugMessages
      }
      def debug(t: Throwable)(message: => String): IO[Unit] = IO.unit
      def info(message: => String): IO[Unit] = IO {
        infoMessages = message :: infoMessages
      }
      def info(t: Throwable)(message: => String): IO[Unit] = IO.unit
      def warn(message: => String): IO[Unit] = IO.unit
      def warn(t: Throwable)(message: => String): IO[Unit] = IO.unit
      def error(message: => String): IO[Unit] = IO.unit
      def error(t: Throwable)(message: => String): IO[Unit] = IO.unit
      def trace(message: => String): IO[Unit] = IO.unit
      def trace(t: Throwable)(message: => String): IO[Unit] = IO.unit

    val mockFs = new FileSystemOps:
      def which(command: String): IO[Option[String]] =
        IO.pure(None) // PATH lookup fails
      def exists(path: String): IO[Boolean] =
        IO.pure(path.endsWith("/.npm-global/bin/claude"))
      def isExecutable(path: String): IO[Boolean] =
        IO.pure(path.endsWith("/.npm-global/bin/claude"))

    CLIDiscovery
      .findClaude(mockFs, mockLogger)
      .map: result =>
        assert(result.isRight)
        assert(debugMessages.contains("Searching for claude in PATH"))
        assert(
          debugMessages
            .contains("Claude not found in PATH, trying common paths")
        )
        assert(debugMessages.exists(_.contains("Trying common paths:")))
        assert(infoMessages.exists(_.contains("Found claude executable at:")))

  test("findClaude logs appropriate warnings and errors when CLI not found"):
    var warnMessages = List.empty[String]
    var errorMessages = List.empty[String]

    val mockLogger = new Logger[IO]:
      def debug(message: => String): IO[Unit] = IO.unit
      def debug(t: Throwable)(message: => String): IO[Unit] = IO.unit
      def info(message: => String): IO[Unit] = IO.unit
      def info(t: Throwable)(message: => String): IO[Unit] = IO.unit
      def warn(message: => String): IO[Unit] = IO {
        warnMessages = message :: warnMessages
      }
      def warn(t: Throwable)(message: => String): IO[Unit] = IO.unit
      def error(message: => String): IO[Unit] = IO {
        errorMessages = message :: errorMessages
      }
      def error(t: Throwable)(message: => String): IO[Unit] = IO.unit
      def trace(message: => String): IO[Unit] = IO.unit
      def trace(t: Throwable)(message: => String): IO[Unit] = IO.unit

    val mockFs = new FileSystemOps:
      def which(command: String): IO[Option[String]] =
        command match
          case "claude" => IO.pure(None)
          case "node"   => IO.pure(None) // Node.js not found
          case _        => IO.pure(None)
      def exists(path: String): IO[Boolean] = IO.pure(false)
      def isExecutable(path: String): IO[Boolean] = IO.pure(false)

    CLIDiscovery
      .findClaude(mockFs, mockLogger)
      .map: result =>
        assert(result.isLeft)
        assert(
          warnMessages.contains("Claude CLI not found in any common paths")
        )
        assert(
          errorMessages.contains(
            "Node.js not found - prerequisite for Claude CLI"
          )
        )
