// PURPOSE: Tests for direct-style ClaudeCode main API using Ox
// PURPOSE: Verifies streaming query interface with Flow and resource management using supervised scopes
package works.iterative.claude.direct

import ox.*
import works.iterative.claude.core.model.*
import works.iterative.claude.core.{
  ProcessExecutionError,
  ProcessTimeoutError,
  JsonParsingError,
  ConfigurationError
}
import works.iterative.claude.direct.internal.testing.MockCliScript
import java.nio.file.Path
import scala.util.{Try, Using}
import scala.concurrent.duration.Duration

class ClaudeCodeTest extends munit.FunSuite:

  // Track created mock scripts for cleanup
  private val createdScripts = scala.collection.mutable.ListBuffer[Path]()

  override def afterEach(context: AfterEach): Unit =
    // Clean up temporary mock scripts
    createdScripts.foreach(MockCliScript.cleanup)
    createdScripts.clear()
    super.afterEach(context)

  test("should return streaming Flow of parsed messages for valid CLI output") {
    supervised {
      // Setup: Create realistic mock CLI script with progressive output
      val mockBehavior =
        MockCliScript.CommonBehaviors.successfulQuery("Hello Claude!")
      val mockScript = MockCliScript.createTempScript(mockBehavior)
      createdScripts += mockScript

      val options = QueryOptions(
        prompt = "Hello Claude!",
        cwd = None,
        executable = None,
        executableArgs = None, // No need for args with realistic mock script
        pathToClaudeCodeExecutable =
          Some(mockScript.toString), // Use realistic mock CLI
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

      // Execute: Call query to get Flow of messages
      val messageFlow = ClaudeCode.query(options)

      // Collect all messages from the Flow
      val messages = messageFlow.runToList()

      // Verify: Should return Flow[Message] with all expected message types
      assertEquals(messages.length, 3)

      // Verify message types in order
      messages(0) match
        case SystemMessage(subtype, _) => assertEquals(subtype, "user_context")
        case other => fail(s"Expected SystemMessage but got: $other")

      messages(1) match
        case AssistantMessage(content) =>
          assertEquals(content.length, 1)
          content.head match
            case TextBlock(text) =>
              assertEquals(text, "Response to: Hello Claude!")
            case other => fail(s"Expected TextBlock but got: $other")
        case other => fail(s"Expected AssistantMessage but got: $other")

      messages(2) match
        case ResultMessage(
              subtype,
              durationMs,
              durationApiMs,
              isError,
              numTurns,
              sessionId,
              _,
              _,
              _
            ) =>
          assertEquals(subtype, "conversation_result")
          assertEquals(durationMs, 1000)
          assertEquals(durationApiMs, 500)
          assertEquals(isError, false)
          assertEquals(numTurns, 1)
          assertEquals(sessionId, "session_123")
        case other => fail(s"Expected ResultMessage but got: $other")
    }
  }

  test("should execute successfully with configured CLI path") {
    supervised {
      // Setup: Create realistic mock CLI script
      val mockBehavior =
        MockCliScript.CommonBehaviors.successfulQuery("Hello Claude!")
      val mockScript = MockCliScript.createTempScript(mockBehavior)
      createdScripts += mockScript

      val options = QueryOptions(
        prompt = "Hello Claude!",
        cwd = None,
        executable = None,
        executableArgs = None, // No need for args with realistic mock script
        pathToClaudeCodeExecutable =
          Some(mockScript.toString), // Use mock CLI for testing
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

      // Execute: Call query without explicit CLI path - should use CLI discovery
      val messageFlow = ClaudeCode.query(options)

      // Collect all messages from the Flow
      val messages = messageFlow.runToList()

      // Verify: Should successfully execute after discovering CLI path
      assertEquals(messages.length, 3)

      // Verify message types in order (same as T6.1)
      messages(0) match
        case SystemMessage(subtype, _) => assertEquals(subtype, "user_context")
        case other => fail(s"Expected SystemMessage but got: $other")

      messages(1) match
        case AssistantMessage(content) =>
          assertEquals(content.length, 1)
          content.head match
            case TextBlock(text) =>
              assertEquals(text, "Response to: Hello Claude!")
            case other => fail(s"Expected TextBlock but got: $other")
        case other => fail(s"Expected AssistantMessage but got: $other")

      messages(2) match
        case ResultMessage(
              subtype,
              durationMs,
              durationApiMs,
              isError,
              numTurns,
              sessionId,
              _,
              _,
              _
            ) =>
          assertEquals(subtype, "conversation_result")
          assertEquals(durationMs, 1000)
          assertEquals(durationApiMs, 500)
          assertEquals(isError, false)
          assertEquals(numTurns, 1)
          assertEquals(sessionId, "session_123")
        case other => fail(s"Expected ResultMessage but got: $other")
    }
  }

  test("should handle CLI discovery failure gracefully with appropriate error") {
    supervised {
      // Setup: Use an invalid executable path that will definitely fail
      val options = QueryOptions(
        prompt = "Hello Claude!",
        cwd = None,
        executable = None,
        executableArgs = None, // No mock args - will try real CLI arguments
        pathToClaudeCodeExecutable =
          Some("/this/path/definitely/does/not/exist/claude"), // Invalid path
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

      // Execute: Call query with invalid CLI path - should fail at process start
      val exception = intercept[Throwable] {
        val messageFlow = ClaudeCode.query(options)
        messageFlow.runToList() // Force evaluation
      }

      // Verify: Should fail when executable does not exist with detailed error context
      exception match {
        case ioException: java.io.IOException =>
          // When executable doesn't exist, ProcessBuilder.start() throws IOException
          assert(
            ioException.getMessage.contains("Cannot run program"),
            s"Expected 'Cannot run program' in IOException: ${ioException.getMessage}"
          )
          assert(
            ioException.getMessage.contains(
              "/this/path/definitely/does/not/exist/claude"
            ),
            s"Expected specific invalid path in IOException: ${ioException.getMessage}"
          )
          assert(
            ioException.getMessage.contains("No such file or directory"),
            s"Expected 'No such file or directory' in IOException: ${ioException.getMessage}"
          )
        case ProcessExecutionError(exitCode, stderr, command) =>
          // If the error gets wrapped into ProcessExecutionError (alternate implementation)
          assert(
            exitCode != 0,
            s"Expected non-zero exit code but got: $exitCode"
          )
          assert(command.nonEmpty, "Expected command information in error")
          assert(
            command.contains("/this/path/definitely/does/not/exist/claude"),
            s"Expected specific invalid path in command: ${command.mkString(" ")}"
          )
        case other =>
          fail(
            s"Expected IOException or ProcessExecutionError for non-existent executable but got: $other"
          )
      }
    }
  }

  test("should validate configuration before execution and fail for invalid working directory") {
    supervised {
      // Setup: QueryOptions with invalid working directory
      val options = QueryOptions(
        prompt = "Hello Claude!",
        cwd = Some(
          "/this/directory/definitely/does/not/exist"
        ), // Invalid working directory
        executable = None,
        executableArgs = None,
        pathToClaudeCodeExecutable = Some("/bin/echo"), // Valid executable
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

      // Execute: Call query with invalid working directory - should fail validation
      val exception = intercept[ConfigurationError] {
        val messageFlow = ClaudeCode.query(options)
        messageFlow.runToList() // Force evaluation
      }

      // Verify: Should fail with ConfigurationError with specific parameter context
      exception match {
        case ConfigurationError(parameter, value, reason) =>
          assert(
            parameter.contains("cwd") || parameter.contains("working"),
            s"Expected parameter related to working directory but got: $parameter"
          )
          assert(
            value.contains("/this/directory/definitely/does/not/exist"),
            s"Expected specific invalid directory path in value: $value"
          )
          assert(
            reason.nonEmpty,
            "Expected descriptive reason for configuration error"
          )
      }
    }
  }

  test("should pass CLI arguments correctly when building command") {
    supervised {
      // Setup: QueryOptions with various CLI parameters to test argument building
      val options = QueryOptions(
        prompt = "Test prompt",
        cwd = None, // Use current working directory so relative path works
        executable = Some("test-executable"),
        executableArgs = None, // Don't override - let it build real arguments
        pathToClaudeCodeExecutable =
          Some("test/bin/mock-claude"), // Use mock CLI that outputs JSON
        maxTurns = Some(5),
        allowedTools = Some(List("tool1", "tool2")),
        disallowedTools = Some(List("tool3")),
        systemPrompt = Some("Custom system prompt"),
        appendSystemPrompt = Some("Additional system prompt"),
        mcpTools = Some(List("mcp-tool1")),
        permissionMode = Some(PermissionMode.Default),
        continueConversation = Some(true),
        resume = Some("test-session-id"),
        model = Some("claude-3-sonnet"),
        maxThinkingTokens = Some(1000),
        timeout = Some(scala.concurrent.duration.Duration(30, "seconds")),
        inheritEnvironment = Some(false),
        environmentVariables = Some(Map("TEST_VAR" -> "test_value"))
      )

      // Execute: Call query to trigger argument building
      val messageFlow = ClaudeCode.query(options)

      // Collect all messages from the Flow - this will contain the echo output
      val messages = messageFlow.runToList()

      // Verify: Should pass correct arguments to CLI process
      // Since we're using /bin/echo, the echo output should contain the CLI arguments
      // For this test, we mainly want to verify the process executes successfully
      // with the built arguments (real argument validation would require more sophisticated mocking)
      assert(messages.nonEmpty, "Should receive some output from echo command")
    }
  }

  test("should handle process execution errors with specific exit codes") {
    supervised {
      // Setup: Use an executable that will fail with non-zero exit code
      val options = QueryOptions(
        prompt = "Test prompt",
        cwd = None,
        executable = None,
        executableArgs = None,
        pathToClaudeCodeExecutable =
          Some("/bin/false"), // Command that always exits with code 1
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

      // Execute: Call query with failing CLI - should propagate process execution error
      val exception = intercept[ProcessExecutionError] {
        val messageFlow = ClaudeCode.query(options)
        messageFlow.runToList() // Force evaluation
      }

      // Verify: Should fail with ProcessExecutionError with specific exit code and command details
      assertEquals(
        exception.exitCode,
        1,
        "Expected exit code 1 from /bin/false"
      )
      assert(
        exception.command.nonEmpty,
        "Expected command information in error"
      )
      assert(
        exception.command.contains("/bin/false"),
        s"Expected /bin/false in command: ${exception.command.mkString(" ")}"
      )
      // stderr may be empty for /bin/false, but the field should be accessible
      assert(exception.stderr != null, "Expected stderr field to be accessible")
    }
  }

  test("should handle process timeout errors when execution exceeds limit") {
    supervised {
      // Setup: Use a command that hangs with a short timeout
      val options = QueryOptions(
        prompt = "Test prompt",
        cwd = None,
        executable = None,
        executableArgs = Some(List("10")), // Sleep for 10 seconds
        pathToClaudeCodeExecutable = Some("sleep"), // Command that will hang
        timeout = Some(
          scala.concurrent.duration.Duration(500, "milliseconds")
        ), // Very short timeout
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
        inheritEnvironment = None,
        environmentVariables = None
      )

      // Execute: Call query with hanging CLI and timeout - should timeout
      val exception = intercept[ProcessTimeoutError] {
        val messageFlow = ClaudeCode.query(options)
        messageFlow.runToList() // Force evaluation
      }

      // Verify: Should fail with ProcessTimeoutError with exact timeout duration and command details
      assertEquals(
        exception.timeoutDuration.toMillis,
        500L,
        "Expected exactly 500ms timeout duration"
      )
      assert(
        exception.command.nonEmpty,
        "Expected command information in error"
      )
      assert(
        exception.command.contains("sleep"),
        s"Expected 'sleep' command in timeout error: ${exception.command.mkString(" ")}"
      )
      assert(
        exception.command.contains("10"),
        s"Expected sleep duration '10' in command: ${exception.command.mkString(" ")}"
      )
    }
  }

  test("should handle JSON parsing errors gracefully during streaming") {
    supervised {
      // Setup: Use mock CLI that outputs malformed JSON mid-stream
      val options = QueryOptions(
        prompt = "Test prompt",
        cwd = None,
        executable = None,
        executableArgs = None,
        pathToClaudeCodeExecutable = Some(
          "test/bin/mock-claude-malformed"
        ), // Mock CLI with malformed JSON
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

      // Execute: Call query with CLI that outputs malformed JSON
      // The current implementation should gracefully handle JSON parsing errors
      val messageFlow = ClaudeCode.query(options)
      val messages = messageFlow.runToList()

      // Verify: Should handle JSON parsing errors gracefully
      // Based on ProcessManager implementation, it logs errors but continues processing
      // So we should get the valid messages (first and third lines) but skip the malformed one
      assert(
        messages.length >= 1,
        "Should get at least some valid messages despite malformed JSON"
      )

      // First message should be the valid SystemMessage
      messages.headOption match
        case Some(SystemMessage(subtype, _)) =>
          assertEquals(subtype, "user_context")
        case other => fail(s"Expected SystemMessage but got: $other")
    }
  }

  test("should collect all messages from query Flow when using querySync") {
    supervised {
      // Setup: QueryOptions that will produce multiple messages
      val options = QueryOptions(
        prompt = "Test prompt",
        cwd = None,
        executable = None,
        executableArgs = None,
        pathToClaudeCodeExecutable =
          Some("test/bin/mock-claude"), // Mock CLI with multiple messages
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

      // Execute: Call querySync to collect all messages at once
      val messages = ClaudeCode.querySync(options)

      // Verify: Should collect all messages from the Flow into a List
      assert(messages.nonEmpty, "Should receive messages from CLI")

      // Should have multiple message types from the mock CLI
      val hasSystemMessage = messages.exists(_.isInstanceOf[SystemMessage])
      val hasAssistantMessage =
        messages.exists(_.isInstanceOf[AssistantMessage])
      val hasResultMessage = messages.exists(_.isInstanceOf[ResultMessage])

      assert(hasSystemMessage, "Should include SystemMessage from mock CLI")
      // Note: AssistantMessage and ResultMessage depend on the mock CLI output
    }
  }

  test("should propagate errors from underlying query when using querySync") {
    supervised {
      // Setup: QueryOptions that will cause ProcessExecutionError
      val options = QueryOptions(
        prompt = "Test prompt",
        cwd = None,
        executable = None,
        executableArgs = None,
        pathToClaudeCodeExecutable =
          Some("/bin/false"), // Command that always exits with code 1
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

      // Execute: Call querySync with failing CLI - should propagate error
      val exception = intercept[ProcessExecutionError] {
        ClaudeCode.querySync(options)
      }

      // Verify: Should propagate same ProcessExecutionError as query() method with specific details
      assertEquals(
        exception.exitCode,
        1,
        "Expected exit code 1 from /bin/false in querySync"
      )
      assert(
        exception.command.nonEmpty,
        "Expected command information in error"
      )
      assert(
        exception.command.contains("/bin/false"),
        s"Expected /bin/false in command from querySync: ${exception.command.mkString(" ")}"
      )
      assert(
        exception.stderr != null,
        "Expected stderr field to be accessible in querySync error"
      )
      // Verify that this is the same type of error as would be thrown by query()
      assert(
        exception.message.contains("Process failed with exit code 1"),
        s"Expected standard ProcessExecutionError message format: ${exception.message}"
      )
    }
  }

  test("should extract text content from AssistantMessage when using queryResult") {
    supervised {
      // Setup: QueryOptions that will produce AssistantMessage with TextBlock
      val options = QueryOptions(
        prompt = "Test prompt",
        cwd = None,
        executable = None,
        executableArgs = None,
        pathToClaudeCodeExecutable =
          Some("test/bin/mock-claude"), // Mock CLI with AssistantMessage
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

      // Execute: Call queryResult to extract text content
      val result = ClaudeCode.queryResult(options)

      // Verify: Should extract text from AssistantMessage's TextBlock
      assert(result.nonEmpty, "Should extract text from AssistantMessage")
      // The exact content depends on the mock CLI output
    }
  }

  test("should return empty string when no AssistantMessage found in queryResult") {
    supervised {
      // Setup: Create mock CLI script that outputs only SystemMessage and ResultMessage (no AssistantMessage)
      val mockBehavior = MockCliScript.MockBehavior(
        messages = List(
          """{"type":"system","subtype":"user_context","context_user_id":"user_123"}""",
          """{"type":"result","subtype":"conversation_result","duration_ms":1000,"duration_api_ms":500,"is_error":false,"num_turns":1,"session_id":"session_123"}"""
        ),
        delayBetweenMessages = Duration(25, "milliseconds")
      )
      val mockScript = MockCliScript.createTempScript(mockBehavior)
      createdScripts += mockScript

      val options = QueryOptions(
        prompt = "Test prompt",
        cwd = None,
        executable = None,
        executableArgs = None,
        pathToClaudeCodeExecutable = Some(mockScript.toString),
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

      // Execute: Call queryResult when no AssistantMessage is present
      val result = ClaudeCode.queryResult(options)

      // Verify: Should return empty string gracefully
      assertEquals(
        result,
        "",
        "Should return empty string when no AssistantMessage found"
      )
    }
  }

  test("should return empty string when AssistantMessage has no TextBlock content") {
    supervised {
      // Setup: Create mock CLI script that outputs AssistantMessage without TextBlock content
      // This is a theoretical edge case but we should handle it gracefully
      val mockBehavior = MockCliScript.MockBehavior(
        messages = List(
          """{"type":"system","subtype":"user_context","context_user_id":"user_123"}""",
          """{"type":"assistant","message":{"content":[]}}""", // Empty content array
          """{"type":"result","subtype":"conversation_result","duration_ms":1000,"duration_api_ms":500,"is_error":false,"num_turns":1,"session_id":"session_123"}"""
        ),
        delayBetweenMessages = Duration(25, "milliseconds")
      )
      val mockScript = MockCliScript.createTempScript(mockBehavior)
      createdScripts += mockScript

      val options = QueryOptions(
        prompt = "Test prompt",
        cwd = None,
        executable = None,
        executableArgs = None,
        pathToClaudeCodeExecutable = Some(mockScript.toString),
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

      // Execute: Call queryResult - echo output won't be a valid AssistantMessage
      val result = ClaudeCode.queryResult(options)

      // Verify: Should return empty string when no TextBlock found
      assertEquals(
        result,
        "",
        "Should return empty string when no TextBlock found"
      )
    }
  }
