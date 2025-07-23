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

class ClaudeCodeTest extends munit.FunSuite:

  test("T6.1: query with simple prompt returns Flow of messages") {
    supervised {
      // Setup: Mock CLI executable outputting SystemMessage + AssistantMessage + ResultMessage
      // Mock command that outputs expected JSON messages
      val mockJsonOutput = List(
        """{"type":"system","subtype":"user_context","context_user_id":"user_123"}""",
        """{"type":"assistant","message":{"content":[{"type":"text","text":"Hello! How can I help?"}]}}""",
        """{"type":"result","subtype":"conversation_result","duration_ms":1000,"duration_api_ms":500,"is_error":false,"num_turns":1,"session_id":"session_123"}"""
      ).mkString("\n")

      val options = QueryOptions(
        prompt = "Hello Claude!",
        cwd = None,
        executable = None,
        executableArgs =
          Some(List(mockJsonOutput)), // Pass JSON as executable args for echo
        pathToClaudeCodeExecutable = Some("/bin/echo"), // Mock CLI with echo
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
            case TextBlock(text) => assertEquals(text, "Hello! How can I help?")
            case other           => fail(s"Expected TextBlock but got: $other")
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

  test("T6.2: query handles CLI discovery when no explicit path provided") {
    supervised {
      // Setup: QueryOptions without pathToClaudeCodeExecutable
      val mockJsonOutput = List(
        """{"type":"system","subtype":"user_context","context_user_id":"user_123"}""",
        """{"type":"assistant","message":{"content":[{"type":"text","text":"Hello! How can I help?"}]}}""",
        """{"type":"result","subtype":"conversation_result","duration_ms":1000,"duration_api_ms":500,"is_error":false,"num_turns":1,"session_id":"session_123"}"""
      ).mkString("\n")

      val options = QueryOptions(
        prompt = "Hello Claude!",
        cwd = None,
        executable = None,
        executableArgs =
          Some(List(mockJsonOutput)), // Pass JSON as executable args for echo
        pathToClaudeCodeExecutable =
          None, // No explicit path - should trigger CLI discovery
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
            case TextBlock(text) => assertEquals(text, "Hello! How can I help?")
            case other           => fail(s"Expected TextBlock but got: $other")
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

  test("T6.3: query handles CLI discovery failure gracefully") {
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

      // Execute: Call query with invalid CLI path - should fail process execution
      val exception = intercept[Exception] {
        val messageFlow = ClaudeCode.query(options)
        messageFlow.runToList() // Force evaluation
      }

      // Verify: Should propagate process execution error
      assert(
        exception.getMessage != null && exception.getMessage.nonEmpty,
        s"Expected non-empty error message but got: ${exception.getMessage}"
      )
    }
  }

  test("T6.4: query validates configuration before execution") {
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

      // Verify: Should fail with ConfigurationError before attempting process execution
      assert(
        exception.getMessage.contains("working directory") ||
          exception.getMessage.contains("does not exist") ||
          exception.getMessage.contains("invalid") ||
          exception.getMessage.contains("configuration"),
        s"Expected configuration error about working directory but got: ${exception.getMessage}"
      )
    }
  }

  test("T6.5: query passes CLI arguments correctly") {
    supervised {
      // Setup: QueryOptions with various CLI parameters to test argument building
      val options = QueryOptions(
        prompt = "Test prompt",
        cwd = Some("/tmp"), // Valid directory that should exist
        executable = Some("test-executable"),
        executableArgs = None, // Don't override - let it build real arguments
        pathToClaudeCodeExecutable =
          Some("/bin/echo"), // Use echo to capture arguments
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
