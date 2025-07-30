// PURPOSE: Full integration tests for the direct-style ClaudeCode implementation
// PURPOSE: Verifies complete end-to-end workflows with realistic mock CLI executables

package works.iterative.claude.direct

import ox.*
import scala.concurrent.duration.*
import works.iterative.claude.core.model.*
import works.iterative.claude.core.model.QueryOptions
import works.iterative.claude.direct.Logger
import works.iterative.claude.direct.internal.testing.TestConstants

class ClaudeCodeIntegrationTest extends munit.FunSuite:

  // Mock Logger for testing
  class MockLogger extends Logger:
    var debugMessages: List[String] = List.empty
    var infoMessages: List[String] = List.empty
    var warnMessages: List[String] = List.empty
    var errorMessages: List[String] = List.empty

    def debug(msg: => String): Unit = debugMessages = msg :: debugMessages
    def info(msg: => String): Unit = infoMessages = msg :: infoMessages
    def warn(msg: => String): Unit = warnMessages = msg :: warnMessages
    def error(msg: => String): Unit = errorMessages = msg :: errorMessages
    def error(msg: => String, exception: Throwable): Unit = errorMessages =
      s"$msg: ${exception.getMessage}" :: errorMessages

  test("should execute complete workflow with comprehensive mock CLI") {
    supervised {
      // Setup: Create a comprehensive mock CLI script
      given MockLogger = MockLogger()

      // Create comprehensive JSON output simulating a full Claude CLI session
      val mockClaudeOutput = List(
        """{"type":"system","subtype":"user_context","context_user_id":"user_123","workspace_id":"workspace_456"}""",
        """{"type":"user","content":"What is 2+2?"}""",
        """{"type":"assistant","message":{"content":[{"type":"text","text":"2+2 equals 4. This is a fundamental arithmetic operation."}]}}""",
        s"""{"type":"result","subtype":"conversation_result","duration_ms":${TestConstants.MockJsonValues.MOCK_DURATION_MS_SLOW},"duration_api_ms":${TestConstants.MockJsonValues.MOCK_DURATION_API_MS_SLOW},"is_error":false,"num_turns":${TestConstants.MockJsonValues.MOCK_NUM_TURNS_SINGLE},"session_id":"${TestConstants.MockJsonValues.MOCK_SESSION_ID_SECONDARY}"}"""
      ).mkString("\n")

      // Full QueryOptions with all parameters to test comprehensive integration
      val options = QueryOptions(
        prompt = "What is 2+2?",
        cwd = Some("/tmp"),
        executable = None,
        executableArgs =
          Some(List(mockClaudeOutput)), // Pass mock output as args to echo
        pathToClaudeCodeExecutable = Some("/bin/echo"),
        maxTurns = Some(TestConstants.TestParameters.MAX_TURNS_TEST),
        allowedTools = Some(List("Read", "Write")),
        disallowedTools = Some(List("Bash")),
        systemPrompt = Some("You are a helpful math tutor."),
        appendSystemPrompt = Some("Be concise in your explanations."),
        mcpTools = Some(List("mcp__filesystem__read_file")),
        permissionMode = Some(PermissionMode.AcceptEdits),
        continueConversation = Some(false),
        resume = None,
        model = Some("claude-3-5-sonnet-20241022"),
        maxThinkingTokens =
          Some(TestConstants.TestParameters.MAX_THINKING_TOKENS_MAX),
        timeout = None,
        inheritEnvironment = Some(true),
        environmentVariables = Some(
          Map(
            "ANTHROPIC_API_KEY" -> "test-api-key",
            "CLAUDE_CLI_MODE" -> "integration-test"
          )
        )
      )

      // Execute: Run complete workflow through ClaudeCode.concurrent.query
      val claude = ClaudeCode.concurrent
      val messageFlow = claude.query(options)
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
        case Some(AssistantMessage(content)) =>
          val textBlocks = content.collect { case TextBlock(text) =>
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

  test(
    "should handle environment variables correctly in end-to-end integration"
  ) {
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

      // Execute: Run complete workflow through ClaudeCode.concurrent.query with environment variables
      val claude = ClaudeCode.concurrent
      val messageFlow = claude.query(options)
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

  test("should handle working directory correctly in end-to-end integration") {
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

      // Execute: Run complete workflow through ClaudeCode.concurrent.query with custom working directory
      val claude = ClaudeCode.concurrent
      val messageFlow = claude.query(options)
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

  test("should discover and execute real CLI when available") {
    supervised {
      given MockLogger = MockLogger()

      // Explicit environmental assumptions - fail fast if prerequisites aren't met
      assume(isClaudeCliInstalled(), "Test requires Claude CLI to be installed")
      assume(isNodeJsAvailable(), "Test requires Node.js to be available")
      assume(
        hasApiKeyOrMockSetup(),
        "Test requires API key configuration or mock setup"
      )

      val options = QueryOptions(
        prompt = "What is 1+1?", // Use a proper prompt
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
          TestConstants.Timeouts.TEST_TIMEOUT_EXTENDED
        ),
        inheritEnvironment = None,
        environmentVariables = None
      )

      // Now test authentically - if we reach here, environment should support the test
      val claude = ClaudeCode.concurrent
      val messageFlow = claude.query(options)
      val messages = messageFlow.runToList()

      // Verify CLI discovery and execution worked
      assert(
        messages.nonEmpty,
        "Should receive messages from CLI execution"
      )

      // Log success for debugging
      println(
        s"T9.4: Successfully discovered and executed Claude CLI with ${messages.length} messages"
      )
    }
  }

  // Environmental assumption helper functions
  private def isClaudeCliInstalled(): Boolean = {
    try {
      val process = ProcessBuilder("claude", "--version").start()
      val exitCode = process.waitFor()
      exitCode == 0
    } catch {
      case _: Exception => false
    }
  }

  private def isNodeJsAvailable(): Boolean = {
    try {
      val process = ProcessBuilder("node", "--version").start()
      val exitCode = process.waitFor()
      exitCode == 0
    } catch {
      case _: Exception => false
    }
  }

  private def hasApiKeyOrMockSetup(): Boolean = {
    // Check for API key in environment
    val hasApiKey = sys.env.contains("ANTHROPIC_API_KEY")

    // Check if mock CLI is available for testing
    val mockCliPath = java.nio.file.Paths.get("./test/bin/mock-claude")
    val hasMockCli = java.nio.file.Files.exists(mockCliPath)

    hasApiKey || hasMockCli
  }
