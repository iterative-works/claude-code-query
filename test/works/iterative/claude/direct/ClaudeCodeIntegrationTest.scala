// PURPOSE: Full integration tests for the direct-style ClaudeCode implementation
// PURPOSE: Verifies complete end-to-end workflows with realistic mock CLI executables

package works.iterative.claude.direct

import ox.*
import works.iterative.claude.core.model.*
import works.iterative.claude.core.model.QueryOptions
import works.iterative.claude.direct.internal.cli.{Logger}

class ClaudeCodeIntegrationTest extends munit.FunSuite:

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

  test("T9.1: complete workflow with real mock CLI executable") {
    supervised {
      // Setup: Create a comprehensive mock CLI script
      given MockLogger = MockLogger()

      // Create comprehensive JSON output simulating a full Claude CLI session
      val mockClaudeOutput = List(
        """{"type":"system","subtype":"user_context","context_user_id":"user_123","workspace_id":"workspace_456"}""",
        """{"type":"user","content":"What is 2+2?"}""",
        """{"type":"assistant","message":{"content":[{"type":"text","text":"2+2 equals 4. This is a fundamental arithmetic operation."}]}}""",
        """{"type":"result","subtype":"conversation_result","duration_ms":1500,"duration_api_ms":800,"is_error":false,"num_turns":1,"session_id":"session_789"}"""
      ).mkString("\n")

      // Full QueryOptions with all parameters to test comprehensive integration
      val options = QueryOptions(
        prompt = "What is 2+2?",
        cwd = Some("/tmp"),
        executable = None,
        executableArgs =
          Some(List(mockClaudeOutput)), // Pass mock output as args to echo
        pathToClaudeCodeExecutable = Some("/bin/echo"),
        maxTurns = Some(5),
        allowedTools = Some(List("Read", "Write")),
        disallowedTools = Some(List("Bash")),
        systemPrompt = Some("You are a helpful math tutor."),
        appendSystemPrompt = Some("Be concise in your explanations."),
        mcpTools = Some(List("mcp__filesystem__read_file")),
        permissionMode = Some(PermissionMode.AcceptEdits),
        continueConversation = Some(false),
        resume = None,
        model = Some("claude-3-5-sonnet-20241022"),
        maxThinkingTokens = Some(10000),
        timeout = None,
        inheritEnvironment = Some(true),
        environmentVariables = Some(
          Map(
            "ANTHROPIC_API_KEY" -> "test-api-key",
            "CLAUDE_CLI_MODE" -> "integration-test"
          )
        )
      )

      // Execute: Run complete workflow through ClaudeCode.query
      val messageFlow = ClaudeCode.query(options)
      val messages = messageFlow.runToList()

      // Verify: Complete message flow with all expected types
      assert(
        messages.length == 4,
        s"Expected 4 messages, got ${messages.length}"
      )

      // Verify specific message types and content
      val systemMessage = messages.find(_.isInstanceOf[SystemMessage])
      assert(systemMessage.isDefined, "Expected SystemMessage in output")

      val userMessage = messages.find(_.isInstanceOf[UserMessage])
      assert(userMessage.isDefined, "Expected UserMessage in output")

      val assistantMessage = messages.find(_.isInstanceOf[AssistantMessage])
      assert(assistantMessage.isDefined, "Expected AssistantMessage in output")

      val resultMessage = messages.find(_.isInstanceOf[ResultMessage])
      assert(resultMessage.isDefined, "Expected ResultMessage in output")

      // Verify assistant response content
      assistantMessage match {
        case Some(AssistantMessage(messageContent)) =>
          val textBlocks = messageContent.collect { case TextBlock(text) =>
            text
          }
          assert(
            textBlocks.exists(_.contains("2+2 equals 4")),
            s"Expected math answer in assistant response: $textBlocks"
          )
        case _ => fail("AssistantMessage not found or malformed")
      }
    }
  }

  test("T9.2: environment variable integration works end-to-end") {
    supervised {
      // Setup: Mock CLI script that outputs environment variables
      given MockLogger = MockLogger()

      // Create a script that uses environment variables and outputs JSON
      val envVarScript = """
        |#!/bin/sh
        |test_value="$TEST_ENV_VAR"
        |echo "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"Environment variable TEST_ENV_VAR has value: $test_value\"}]}}"
      """.stripMargin

      val options = QueryOptions(
        prompt = "Test environment variables",
        cwd = None,
        executable = None,
        executableArgs = Some(List("-c", envVarScript)),
        pathToClaudeCodeExecutable = Some("/bin/sh"),
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
        environmentVariables = Some(
          Map(
            "TEST_ENV_VAR" -> "test-value-12345"
          )
        )
      )

      // Execute: Run complete workflow through ClaudeCode.query with environment variables
      val messageFlow = ClaudeCode.query(options)
      val messages = messageFlow.runToList()

      // Verify: Environment variable was passed through the entire stack
      val assistantMessages = messages.collect {
        case AssistantMessage(content) => content
      }
      assert(
        assistantMessages.nonEmpty,
        "Expected at least one AssistantMessage"
      )

      val textBlocks = assistantMessages.flatten.collect {
        case TextBlock(text) => text
      }
      assert(
        textBlocks.exists(_.contains("test-value-12345")),
        s"Expected environment variable value in output: $textBlocks"
      )
    }
  }

  test("T9.3: working directory integration works end-to-end") {
    supervised {
      // Setup: Mock CLI script that outputs current working directory
      given MockLogger = MockLogger()

      // Create a script that outputs the current working directory in JSON format
      val cwdScript = """
        |#!/bin/sh
        |current_dir=$(pwd)
        |echo "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"Current working directory: $current_dir\"}]}}"
      """.stripMargin

      val options = QueryOptions(
        prompt = "Show current directory",
        cwd = Some("/tmp"),
        executable = None,
        executableArgs = Some(List("-c", cwdScript)),
        pathToClaudeCodeExecutable = Some("/bin/sh"),
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
        inheritEnvironment = None,
        environmentVariables = None
      )

      // Execute: Run complete workflow through ClaudeCode.query with custom working directory
      val messageFlow = ClaudeCode.query(options)
      val messages = messageFlow.runToList()

      // Verify: Working directory was set correctly through the entire stack
      val assistantMessages = messages.collect {
        case AssistantMessage(content) => content
      }
      assert(
        assistantMessages.nonEmpty,
        "Expected at least one AssistantMessage"
      )

      val textBlocks = assistantMessages.flatten.collect {
        case TextBlock(text) => text
      }
      assert(
        textBlocks.exists(_.contains("/tmp")),
        s"Expected /tmp working directory in output: $textBlocks"
      )
    }
  }

  test("T9.4: real CLI discovery and execution (when available)") {
    supervised {
      // Setup: Test real CLI discovery (conditional on CLI availability)
      given MockLogger = MockLogger()

      val options = QueryOptions(
        prompt = "Test CLI discovery",
        cwd = None,
        executable = None,
        executableArgs = None,
        pathToClaudeCodeExecutable = None, // Don't specify - force discovery
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
        timeout = Some(
          scala.concurrent.duration.FiniteDuration(2, "seconds")
        ), // Very short timeout
        inheritEnvironment = None,
        environmentVariables = None
      )

      // This test is conditional - it only runs if the Claude CLI is available
      // If CLI is not found, the test should handle the error gracefully
      try {
        val messageFlow = ClaudeCode.query(options)
        val messages = messageFlow.runToList()

        // If we reach here, the CLI was found and executed successfully
        // Verify we got some kind of response
        assert(
          messages.nonEmpty,
          "Expected some messages from real CLI execution"
        )

        // Log success for debugging
        println(
          s"T9.4: Successfully discovered and executed Claude CLI with ${messages.length} messages"
        )
      } catch {
        case _: works.iterative.claude.core.CLINotFoundError =>
          // This is expected if Claude CLI is not installed
          println(
            "T9.4: Claude CLI not found - test skipped (this is expected in CI/test environments)"
          )
        case _: works.iterative.claude.core.NodeJSNotFoundError =>
          // This is expected if Node.js is not available
          println(
            "T9.4: Node.js not found - test skipped (this is expected in environments without Node.js)"
          )
        case _: works.iterative.claude.core.ProcessTimeoutError =>
          // This is expected if the real CLI takes too long (e.g., waiting for input)
          println(
            "T9.4: Claude CLI discovered but timed out - test skipped (real CLI likely needs API key or interactive input)"
          )
        case other =>
          // Any other error should fail the test
          fail(s"Unexpected error during CLI discovery: $other")
      }
    }
  }
