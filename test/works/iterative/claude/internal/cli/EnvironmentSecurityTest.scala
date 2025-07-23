package works.iterative.claude.internal.cli

import munit.CatsEffectSuite
import cats.effect.IO
import works.iterative.claude.QueryOptions
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.testing.TestingLogger

class EnvironmentSecurityTest extends CatsEffectSuite:

  test("ProcessExecutionError does not leak environment variable values"):
    // Test that when process fails, error message doesn't contain environment variable values
    val secretValue = "secret_api_key_12345"
    val options = QueryOptions(
      prompt = "test",
      environmentVariables = Some(Map("API_KEY" -> secretValue))
    )

    val processManager = ProcessManager.default
    val processBuilder = processManager.configureProcessBuilder(
      "nonexistent_command_that_will_fail",
      List("--some-arg"),
      options
    )

    // This should fail with ProcessExecutionError
    val logger = TestingLogger.impl[IO]()
    given Logger[IO] = logger
    val result = processManager
      .executeProcess(
        processBuilder,
        options,
        "nonexistent_command_that_will_fail",
        List("--some-arg")
      )
      .compile
      .toList
      .attempt

    result.map { errorOrResult =>
      errorOrResult match
        case Left(error) =>
          // Verify that the error message doesn't contain the secret value
          val errorMessage = error.getMessage
          assert(
            !errorMessage.contains(secretValue),
            s"Error message should not contain environment variable value '$secretValue'. Got: $errorMessage"
          )
        case Right(_) =>
          fail("Expected process to fail but it succeeded")
    }

  test("ProcessTimeoutError does not leak environment variable values"):
    // Test that timeout errors don't leak environment variables
    val secretValue = "secret_timeout_key_67890"
    val options = QueryOptions(
      prompt = "test",
      environmentVariables = Some(Map("TIMEOUT_KEY" -> secretValue)),
      timeout = Some(
        scala.concurrent.duration.Duration.fromNanos(1)
      ) // Very short timeout
    )

    val processManager = ProcessManager.default
    val processBuilder = processManager.configureProcessBuilder(
      "sleep", // This should timeout
      List("10"), // Sleep for 10 seconds
      options
    )

    val logger = TestingLogger.impl[IO]()
    given Logger[IO] = logger
    val result = processManager
      .executeProcess(
        processBuilder,
        options,
        "sleep",
        List("10")
      )
      .compile
      .toList
      .attempt

    result.map { errorOrResult =>
      errorOrResult match
        case Left(error) =>
          val errorMessage = error.getMessage
          assert(
            !errorMessage.contains(secretValue),
            s"Timeout error message should not contain environment variable value '$secretValue'. Got: $errorMessage"
          )
        case Right(_) =>
          fail("Expected process to timeout but it succeeded")
    }

  test("ProcessExecutionError only contains command and args, not environment"):
    // Verify that ProcessExecutionError structure is secure
    val secretValue = "secret_structure_key_99999"
    val options = QueryOptions(
      prompt = "test",
      environmentVariables = Some(Map("STRUCTURE_KEY" -> secretValue))
    )

    // Create a ProcessExecutionError directly to test its structure
    val error = ProcessExecutionError(
      exitCode = 1,
      stderr = "Some stderr output",
      command = List("claude", "--some-arg")
    )

    IO {
      val errorMessage = error.getMessage
      // Error message should contain command and args
      assert(errorMessage.contains("claude"))
      assert(errorMessage.contains("--some-arg"))
      // But should NOT contain environment variables
      assert(!errorMessage.contains(secretValue))
    }
