// PURPOSE: Tests for direct-style ClaudeCode main API using Ox
// PURPOSE: Verifies streaming query interface with Flow and resource management using supervised scopes
package works.iterative.claude.direct

import ox.*
import works.iterative.claude.core.model.*
import works.iterative.claude.core.{
  ProcessExecutionError,
  ProcessTimeoutError,
  JsonParsingError
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
        executableArgs = Some(List(mockJsonOutput)), // Pass JSON as executable args for echo
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
