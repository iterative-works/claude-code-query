// PURPOSE: Test environment configuration edge cases for the direct-style ProcessManager
// PURPOSE: Verifies handling of special characters, edge cases, and security in environment variables

package works.iterative.claude.direct.internal.cli

import ox.*
import works.iterative.claude.core.model.*
import works.iterative.claude.core.model.QueryOptions
import works.iterative.claude.direct.internal.cli.{ProcessManager, Logger}

class EnvironmentTest extends munit.FunSuite:

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

  test("T8.1: handles environment variable names with special characters") {
    supervised {
      // Setup: Mock CLI executable with environment variables containing special characters
      given MockLogger = MockLogger()

      // Test environment variables with underscores, numbers, dots
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

      // Create a script that outputs environment variables in JSON format
      val envCheckScript = """
        |env_vars=$(env | grep -E '^(MY_VAR_1|VAR_WITH_NUMBERS_123|SPECIAL_VAR_NAME)=' | sort | tr '\n' ',' | sed 's/,$//')
        |echo "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"Environment variables: $env_vars\"}]}}"
      """.stripMargin

      val testScript = "/bin/sh"
      val args = List("-c", envCheckScript)

      val messages = ProcessManager.executeProcess(testScript, args, options)

      val output = messages.map(_.toString).mkString("\n")

      // Verify all variables are set correctly
      assert(
        output.contains("MY_VAR_1=value1"),
        s"Expected MY_VAR_1=value1 in output: $output"
      )
      assert(
        output.contains("VAR_WITH_NUMBERS_123=value2"),
        s"Expected VAR_WITH_NUMBERS_123=value2 in output: $output"
      )
      assert(
        output.contains("SPECIAL_VAR_NAME=value3"),
        s"Expected SPECIAL_VAR_NAME=value3 in output: $output"
      )
    }
  }
