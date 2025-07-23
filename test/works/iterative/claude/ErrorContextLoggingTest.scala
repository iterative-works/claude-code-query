package works.iterative.claude

import cats.effect.IO
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import works.iterative.claude.internal.cli.{
  ConfigurationError,
  ProcessExecutionError,
  JsonParsingError
}

class ErrorContextLoggingTest extends CatsEffectSuite:

  test("ConfigurationError scenarios are logged with appropriate context"):
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

    val invalidOptions = QueryOptions(
      prompt = "test",
      cwd = Some("/nonexistent/path")
    )

    ClaudeCode
      .logConfigurationError(mockLogger, invalidOptions)
      .map: result =>
        // Should log configuration validation error
        assert(errorMessages.nonEmpty)
        assert(
          errorMessages.exists(_.contains("Configuration validation failed"))
        )
        assert(
          errorMessages.exists(_.contains("Working directory does not exist"))
        )
        assert(errorMessages.exists(_.contains("/nonexistent/path")))

  test("ProcessExecutionError scenarios include enhanced context logging"):
    var errorMessages = List.empty[String]

    val mockLogger = new Logger[IO]:
      def debug(message: => String): IO[Unit] = IO.unit
      def debug(t: Throwable)(message: => String): IO[Unit] = IO.unit
      def info(message: => String): IO[Unit] = IO.unit
      def info(t: Throwable)(message: => String): IO[Unit] = IO.unit
      def warn(message: => String): IO[Unit] = IO.unit
      def warn(t: Throwable)(message: => String): IO[Unit] = IO.unit
      def error(message: => String): IO[Unit] = IO {
        errorMessages = message :: errorMessages
      }
      def error(t: Throwable)(message: => String): IO[Unit] = IO.unit
      def trace(message: => String): IO[Unit] = IO.unit
      def trace(t: Throwable)(message: => String): IO[Unit] = IO.unit

    val processError = ProcessExecutionError(
      exitCode = 1,
      stderr = "Authentication failed",
      command = List("claude", "query", "test")
    )

    ClaudeCode
      .logProcessExecutionError(mockLogger, processError)
      .map: result =>
        // Should log enhanced process execution error context
        assert(errorMessages.nonEmpty)
        assert(errorMessages.exists(_.contains("Process execution failed")))
        assert(errorMessages.exists(_.contains("exit code 1")))
        assert(errorMessages.exists(_.contains("Authentication failed")))
        assert(errorMessages.exists(_.contains("claude query test")))

  test("JsonParsingError scenarios include enhanced context logging"):
    var errorMessages = List.empty[String]

    val mockLogger = new Logger[IO]:
      def debug(message: => String): IO[Unit] = IO.unit
      def debug(t: Throwable)(message: => String): IO[Unit] = IO.unit
      def info(message: => String): IO[Unit] = IO.unit
      def info(t: Throwable)(message: => String): IO[Unit] = IO.unit
      def warn(message: => String): IO[Unit] = IO.unit
      def warn(t: Throwable)(message: => String): IO[Unit] = IO.unit
      def error(message: => String): IO[Unit] = IO {
        errorMessages = message :: errorMessages
      }
      def error(t: Throwable)(message: => String): IO[Unit] = IO.unit
      def trace(message: => String): IO[Unit] = IO.unit
      def trace(t: Throwable)(message: => String): IO[Unit] = IO.unit

    val jsonError = JsonParsingError(
      line = "invalid json content",
      lineNumber = 42,
      cause = new RuntimeException("Unexpected character")
    )

    ClaudeCode
      .logJsonParsingError(mockLogger, jsonError)
      .map: result =>
        // Should log enhanced JSON parsing error context
        assert(errorMessages.nonEmpty)
        assert(errorMessages.exists(_.contains("JSON parsing failed")))
        assert(errorMessages.exists(_.contains("line 42")))
        assert(errorMessages.exists(_.contains("invalid json content")))
        assert(errorMessages.exists(_.contains("Unexpected character")))
