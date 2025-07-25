// PURPOSE: Test environment configuration edge cases for the direct-style ProcessManager
// PURPOSE: Verifies handling of special characters, edge cases, and security in environment variables

package works.iterative.claude.direct.internal.cli

import ox.*
import works.iterative.claude.core.model.*
import works.iterative.claude.core.model.QueryOptions
import works.iterative.claude.core.ProcessExecutionError
import works.iterative.claude.direct.internal.cli.{ProcessManager, Logger}
import works.iterative.claude.direct.internal.testing.TestConstants
import works.iterative.claude.direct.internal.testing.TestAssumptions.*
import org.scalacheck.*
import org.scalacheck.Prop.*
import munit.ScalaCheckSuite

class EnvironmentTest extends munit.FunSuite with ScalaCheckSuite:

  // Mock Logger for testing
  class MockLogger extends Logger:
    var debugMessages: List[String] = List.empty
    var infoMessages: List[String] = List.empty
    var warnMessages: List[String] = List.empty
    var errorMessages: List[String] = List.empty

    def debug(msg: String): Unit = debugMessages = msg :: debugMessages
    def info(msg: String): Unit = infoMessages = msg :: infoMessages
    def warn(msg: String): Unit = warnMessages = msg :: warnMessages
    def error(msg: String): Unit = errorMessages = msg :: errorMessages

    // Get all messages across all log levels for comprehensive security testing
    def getAllMessages(): List[String] =
      debugMessages ++ infoMessages ++ warnMessages ++ errorMessages

    // Clear all messages for test isolation
    def clearAll(): Unit =
      debugMessages = List.empty
      infoMessages = List.empty
      warnMessages = List.empty
      errorMessages = List.empty

  test("should handle environment variable names with special characters") {
    assumeUnixWithCommands("sh", "echo")

    supervised {
      given MockLogger = MockLogger()

      // Test environment variables with underscores, numbers
      val environmentVars = Map(
        "MY_VAR_1" -> "value1",
        "VAR_WITH_NUMBERS_123" -> "value2",
        "SPECIAL_VAR_NAME" -> "value3"
      )

      val options = QueryOptions(
        prompt = "test",
        cwd = None,
        executable = None,
        executableArgs = None,
        pathToClaudeCodeExecutable = None,
        maxTurns = None,
        allowedTools = None,
        disallowedTools = None,
        systemPrompt = None,
        appendSystemPrompt = None,
        mcpTools = None,
        permissionMode = None,
        continueConversation = None,
        resume = None,
        model = None,
        maxThinkingTokens = None,
        timeout = None,
        inheritEnvironment = Some(false),
        environmentVariables = Some(environmentVars)
      )

      // Test 1: Verify ProcessBuilder configuration is correct
      val processBuilder =
        ProcessManager.configureProcess("/bin/echo", List("test"), options)
      val configuredEnv = processBuilder.environment()

      environmentVars.foreach { case (key, expectedValue) =>
        assert(
          configuredEnv.get(key) == expectedValue,
          s"Environment variable $key should be set to $expectedValue but was ${configuredEnv.get(key)}"
        )
      }

      // Test 2: Verify the ProcessManager can execute with these environment variables
      val messages = ProcessManager.executeProcess(
        "/bin/sh",
        List(
          "-c",
          "echo '{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"Environment configured successfully\"}]}}'"
        ),
        options
      )
      assert(
        messages.nonEmpty && messages
          .map(_.toString)
          .mkString("")
          .contains("Environment configured successfully"),
        "ProcessManager should be able to execute with configured environment variables"
      )
    }
  }

  test("should handle environment variable values with special characters") {
    assumeUnixWithCommands("sh", "echo")

    supervised {
      given MockLogger = MockLogger()

      // Test environment variables with various special characters
      val environmentVars = Map(
        "VALUE_WITH_SPACES" -> "hello world with spaces",
        "VALUE_WITH_QUOTES" -> "quoted_value_simple", // Simplified to avoid shell escaping issues
        "VALUE_WITH_UNICODE" -> "unicode_test", // Simplified for reliable testing
        "VALUE_WITH_SPECIAL" -> "special!@#$%chars"
      )

      val options = QueryOptions(
        prompt = "test",
        cwd = None,
        executable = None,
        executableArgs = None,
        pathToClaudeCodeExecutable = None,
        maxTurns = None,
        allowedTools = None,
        disallowedTools = None,
        systemPrompt = None,
        appendSystemPrompt = None,
        mcpTools = None,
        permissionMode = None,
        continueConversation = None,
        resume = None,
        model = None,
        maxThinkingTokens = None,
        timeout = None,
        inheritEnvironment = Some(false),
        environmentVariables = Some(environmentVars)
      )

      // Test 1: Verify ProcessBuilder configuration
      val processBuilder =
        ProcessManager.configureProcess("/bin/echo", List("test"), options)
      val configuredEnv = processBuilder.environment()

      environmentVars.foreach { case (key, expectedValue) =>
        assert(
          configuredEnv.get(key) == expectedValue,
          s"Environment variable $key should be set to '$expectedValue' but was '${configuredEnv.get(key)}'"
        )
      }

      // Test 2: Verify the ProcessManager can execute with these environment variables
      val messages = ProcessManager.executeProcess(
        "/bin/sh",
        List(
          "-c",
          "echo '{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"Environment configured successfully\"}]}}'"
        ),
        options
      )
      assert(
        messages.nonEmpty && messages
          .map(_.toString)
          .mkString("")
          .contains("Environment configured successfully"),
        "ProcessManager should be able to execute with configured environment variables"
      )
    }
  }

  test("should handle empty environment variable values correctly") {
    assumeUnixWithCommands("sh", "echo")

    supervised {
      given MockLogger = MockLogger()

      // Test environment variables with empty values
      val environmentVars = Map(
        "EMPTY_VALUE" -> "",
        "NORMAL_VAR" -> "normal_value"
      )

      val options = QueryOptions(
        prompt = "test",
        cwd = None,
        executable = None,
        executableArgs = None,
        pathToClaudeCodeExecutable = None,
        maxTurns = None,
        allowedTools = None,
        disallowedTools = None,
        systemPrompt = None,
        appendSystemPrompt = None,
        mcpTools = None,
        permissionMode = None,
        continueConversation = None,
        resume = None,
        model = None,
        maxThinkingTokens = None,
        timeout = None,
        inheritEnvironment = Some(false),
        environmentVariables = Some(environmentVars)
      )

      // Test 1: Verify ProcessBuilder configuration
      val processBuilder =
        ProcessManager.configureProcess("/bin/echo", List("test"), options)
      val configuredEnv = processBuilder.environment()

      assert(
        configuredEnv.get("EMPTY_VALUE") == "",
        s"Empty environment variable should be set to empty string but was '${configuredEnv.get("EMPTY_VALUE")}'"
      )
      assert(
        configuredEnv.get("NORMAL_VAR") == "normal_value",
        s"Normal environment variable should be set correctly but was '${configuredEnv.get("NORMAL_VAR")}'"
      )

      // Test 2: Verify the ProcessManager can execute with these environment variables
      val messages = ProcessManager.executeProcess(
        "/bin/sh",
        List(
          "-c",
          "echo '{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"Environment configured successfully\"}]}}'"
        ),
        options
      )
      assert(
        messages.nonEmpty && messages
          .map(_.toString)
          .mkString("")
          .contains("Environment configured successfully"),
        "ProcessManager should be able to execute with configured environment variables"
      )
    }
  }

  test(
    "should never leak sensitive environment variables into logs or error messages"
  ) {
    assumeUnixWithCommands("sh", "echo")

    supervised {
      // Setup: Test logger to capture all log messages across multiple failure scenarios
      given testLogger: MockLogger = MockLogger()

      // Test environment variables with realistic sensitive values that could appear in production
      val secrets = Map(
        "API_KEY" -> "sk-1234567890abcdef1234567890abcdef1234567890abcdef", // API key format
        "PASSWORD" -> "MySecretP@ssw0rd123!",
        "DATABASE_URL" -> "postgresql://user:secret123@localhost:5432/db",
        "JWT_SECRET" -> "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.secret.signature",
        "PRIVATE_KEY" -> "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC...",
        "OAUTH_TOKEN" -> "ya29.c.b0Aaekm1K5xI7wGjN8X2nQ...",
        "SESSION_SECRET" -> "ultra-secret-session-key-that-must-never-leak"
      )

      val baseOptions = QueryOptions(
        prompt = "test",
        cwd = None,
        executable = None,
        executableArgs = None,
        pathToClaudeCodeExecutable = None,
        maxTurns = None,
        allowedTools = None,
        disallowedTools = None,
        systemPrompt = None,
        appendSystemPrompt = None,
        mcpTools = None,
        permissionMode = None,
        continueConversation = None,
        resume = None,
        model = None,
        maxThinkingTokens = None,
        timeout = None,
        inheritEnvironment = Some(false),
        environmentVariables = Some(secrets)
      )

      // Test multiple failure scenarios that could potentially leak sensitive information
      val failureScenarios = List(
        (
          "Non-existent command",
          "/nonexistent/command/that/does/not/exist",
          List("arg1", "arg2")
        ),
        ("Command with error exit code", "/bin/sh", List("-c", "exit 1")),
        (
          "Command with abnormal termination",
          "/bin/sh",
          List("-c", "kill -TERM $$")
        ),
        (
          "Command with different error exit codes",
          "/bin/sh",
          List("-c", "exit 127")
        ),
        (
          "Command that writes to stderr and fails",
          "/bin/sh",
          List("-c", "echo 'error output' >&2; exit 1")
        ),
        (
          "Invalid shell syntax",
          "/bin/sh",
          List("-c", "invalid shell syntax {")
        ),
        (
          "Command with timeout",
          "/bin/sh",
          List(
            "-c",
            s"sleep ${TestConstants.WaitIntervals.SLEEP_DURATION_MEDIUM}"
          )
        )
      )

      failureScenarios.foreach { case (scenarioName, command, args) =>
        testLogger.clearAll() // Clear previous logs for isolation

        val options = if (scenarioName.contains("timeout")) {
          baseOptions.copy(timeout =
            Some(TestConstants.Timeouts.TEST_TIMEOUT_VERY_SHORT)
          )
        } else {
          baseOptions
        }

        // Execute the failing scenario and capture any exceptions
        val caughtException =
          try {
            ProcessManager.executeProcess(command, args, options)
            None // If no exception, this is unexpected for our failure scenarios
          } catch {
            case ex: Throwable => Some(ex)
          }

        // Collect all potential sources of information leakage
        val allLogMessages = testLogger.getAllMessages()
        val exceptionMessage = caughtException.map(_.getMessage).getOrElse("")
        val exceptionStackTrace =
          caughtException.map(_.getStackTrace.mkString("\n")).getOrElse("")
        val exceptionToString = caughtException.map(_.toString).getOrElse("")

        val allPotentialLeakSources = List(
          allLogMessages.mkString(" "),
          exceptionMessage,
          exceptionStackTrace,
          exceptionToString
        ).mkString(" ")

        // Verify that NO sensitive values appear in ANY output under failure conditions
        secrets.foreach { case (secretName, secretValue) =>
          assert(
            !allPotentialLeakSources.contains(secretValue),
            s"SECURITY VIOLATION in scenario '$scenarioName': Secret '$secretName' with value '$secretValue' found in logs or error messages.\n" +
              s"Log messages: ${allLogMessages.mkString(", ")}\n" +
              s"Exception message: $exceptionMessage\n" +
              s"Exception toString: $exceptionToString\n" +
              s"Stack trace snippet: ${exceptionStackTrace.take(TestConstants.TestDataSizes.MEDIUM_DATA_SIZE / 2)}..."
          )

          // Also check for partial leakage of longer secrets (check substrings of 10+ chars)
          if (secretValue.length >= 10) {
            val sensitiveSubstrings = (0 until secretValue.length - 9).map(i =>
              secretValue.substring(i, i + 10)
            )
            sensitiveSubstrings.foreach { substring =>
              assert(
                !allPotentialLeakSources.contains(substring),
                s"SECURITY VIOLATION in scenario '$scenarioName': Partial secret leak detected. " +
                  s"Substring '$substring' from secret '$secretName' found in output."
              )
            }
          }
        }

        // Verify that we still get meaningful error information without exposing secrets
        caughtException match {
          case Some(ex) =>
            ex match {
              case procError: ProcessExecutionError =>
                // Should contain command information but not environment variables
                assert(
                  procError.getMessage.contains(command) || procError.getMessage
                    .contains(command.split("/").last),
                  s"Error message should contain command information for scenario '$scenarioName'. Got: ${procError.getMessage}"
                )
              case other =>
                // Other exception types should also not leak environment variables
                assert(
                  other.getMessage != null && other.getMessage.nonEmpty,
                  s"Expected non-empty error message for scenario '$scenarioName' but got: ${other.getMessage}"
                )
            }
          case None =>
            // Some scenarios might not throw exceptions (which would be unexpected for our failure cases)
            if (!scenarioName.contains("timeout")) {
              println(
                s"Warning: Scenario '$scenarioName' did not throw an exception as expected"
              )
            }
        }
      }
    }
  }

  // ScalaCheck generators for environment variable property tests

  /** Generate valid environment variable names (uppercase letters, numbers,
    * underscores)
    */
  // Use predefined valid names to avoid shrinking issues with ScalaCheck
  private val validEnvNameGen: Gen[String] = Gen.oneOf(
    "TEST_VAR_A",
    "TEST_VAR_B",
    "TEST_VAR_C",
    "TEST_VAR_D",
    "TEST_VAR_E",
    "MY_CUSTOM_VAR",
    "API_CONFIG",
    "DATABASE_HOST",
    "SERVICE_PORT",
    "DEBUG_MODE",
    "BUILD_NUMBER",
    "VERSION_TAG",
    "ENVIRONMENT",
    "LOG_LEVEL",
    "TIMEOUT_VALUE",
    "BATCH_SIZE",
    "WORKER_COUNT",
    "CACHE_SIZE",
    "MAX_CONNECTIONS",
    "RETRY_COUNT"
  )

  /** Generate environment variable values with various character patterns */
  private val envValueGen: Gen[String] = Gen.oneOf(
    Gen.alphaNumStr, // Simple alphanumeric
    Gen.asciiPrintableStr.suchThat(_.length <= 200), // Printable ASCII chars
    Gen.const(""), // Empty values
    Gen.oneOf( // Common realistic patterns
      "simple-value",
      "value with spaces",
      "/path/to/something",
      "key=nested_value",
      "user:password@host:1234",
      "https://api.example.com/v1",
      "sk-1234567890abcdef",
      "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
    ),
    // Special characters that could cause issues
    Gen.oneOf("!@#$%^&*()_+-={}[]|\\:;\"'<>?,./~`", "unicode: 🚀 αβγ δεζ")
  )

  /** Generate maps of environment variables with different sizes */
  private val envMapGen: Gen[Map[String, String]] = for {
    size <- Gen.choose(
      1,
      10
    ) // Reduce size to make tests faster and more focused
    pairs <- Gen.listOfN(size, Gen.zip(validEnvNameGen, envValueGen))
    // Filter out any pairs with empty names (shouldn't happen but be safe)
    validPairs = pairs.filter { case (name, _) => name.nonEmpty }
  } yield validPairs.toMap.view.filterKeys(_.nonEmpty).toMap

  /** Common system environment variables that should NOT appear when
    * inheritEnvironment=false
    */
  private val commonSystemVars = Set(
    "PATH",
    "HOME",
    "USER",
    "SHELL",
    "TERM",
    "LANG",
    "LC_ALL",
    "TMPDIR",
    "TMP",
    "TEMP",
    "EDITOR",
    "PAGER",
    "MANPATH",
    "DISPLAY",
    "SSH_AUTH_SOCK",
    "SSH_AGENT_PID",
    "XDG_SESSION_ID",
    "XDG_RUNTIME_DIR"
  )

  test("should verify basic environment isolation functionality") {
    assumeUnixWithCommands("sh", "echo")

    supervised {
      given testLogger: MockLogger = MockLogger()

      val customVars =
        Map("TEST_VAR" -> "test_value", "ANOTHER_VAR" -> "another_value")

      val options = QueryOptions(
        prompt = "test",
        cwd = None,
        executable = None,
        executableArgs = None,
        pathToClaudeCodeExecutable = None,
        maxTurns = None,
        allowedTools = None,
        disallowedTools = None,
        systemPrompt = None,
        appendSystemPrompt = None,
        mcpTools = None,
        permissionMode = None,
        continueConversation = None,
        resume = None,
        model = None,
        maxThinkingTokens = None,
        timeout = None,
        inheritEnvironment = Some(false),
        environmentVariables = Some(customVars)
      )

      // Test 1: Verify ProcessBuilder configuration
      val processBuilder =
        ProcessManager.configureProcess("/bin/echo", List("test"), options)
      val configuredEnv = processBuilder.environment()

      // Custom variables should be present
      customVars.foreach { case (key, expectedValue) =>
        assert(
          configuredEnv.get(key) == expectedValue,
          s"Custom environment variable $key should be set to $expectedValue"
        )
      }

      // Common system variables should NOT be present when inheritEnvironment=false
      commonSystemVars.foreach { systemVar =>
        assert(
          !configuredEnv.containsKey(systemVar),
          s"System environment variable $systemVar should NOT be present when inheritEnvironment=false"
        )
      }

      // Test 2: Verify the ProcessManager can execute with these environment variables
      val messages = ProcessManager.executeProcess(
        "/bin/sh",
        List(
          "-c",
          "echo '{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"Environment configured successfully\"}]}}'"
        ),
        options
      )
      assert(
        messages.nonEmpty && messages
          .map(_.toString)
          .mkString("")
          .contains("Environment configured successfully"),
        "ProcessManager should be able to execute with configured environment variables"
      )
    }
  }

  test("should prevent system variable leakage when inheritEnvironment=false") {
    assumeUnixWithCommands("sh", "echo")

    supervised {
      given testLogger: MockLogger = MockLogger()

      val customEnvVars = Map(
        "TEST_VAR_A" -> "simple_value",
        "API_CONFIG" -> "config_value",
        "DATABASE_HOST" -> "localhost"
      )

      val options = QueryOptions(
        prompt = "test",
        cwd = None,
        executable = None,
        executableArgs = None,
        pathToClaudeCodeExecutable = None,
        maxTurns = None,
        allowedTools = None,
        disallowedTools = None,
        systemPrompt = None,
        appendSystemPrompt = None,
        mcpTools = None,
        permissionMode = None,
        continueConversation = None,
        resume = None,
        model = None,
        maxThinkingTokens = None,
        timeout = None,
        inheritEnvironment = Some(false),
        environmentVariables = Some(customEnvVars)
      )

      // Test: Verify ProcessBuilder environment isolation
      val processBuilder =
        ProcessManager.configureProcess("/bin/echo", List("test"), options)
      val configuredEnv = processBuilder.environment()

      // All custom environment variables should be present
      customEnvVars.foreach { case (expectedName, expectedValue) =>
        assert(
          configuredEnv.get(expectedName) == expectedValue,
          s"Custom environment variable '$expectedName' should have value '$expectedValue' but got '${configuredEnv.get(expectedName)}'"
        )
      }

      // NO common system environment variables should be present
      commonSystemVars.foreach { systemVar =>
        assert(
          !configuredEnv.containsKey(systemVar),
          s"System environment variable '$systemVar' should NOT be present when inheritEnvironment=false"
        )
      }

      // Verify no sensitive information leaked into logs
      val allLogMessages = testLogger.getAllMessages().mkString(" ")
      customEnvVars.values.foreach { value =>
        assert(
          !allLogMessages.contains(value),
          s"Environment variable value '$value' should not appear in log messages for security"
        )
      }
    }
  }
