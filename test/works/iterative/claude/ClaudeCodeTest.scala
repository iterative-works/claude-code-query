package works.iterative.claude

import cats.effect.IO
import fs2.Stream
import munit.CatsEffectSuite

class ClaudeCodeTest extends CatsEffectSuite:

  // Mock implementation for testing purposes
  object MockClaudeCode extends ClaudeCode:
    def query(options: QueryOptions): Stream[IO, Message] =
      Stream.emit(
        AssistantMessage(List(TextBlock("4")))
      ) ++ Stream.emit(
        ResultMessage(
          subtype = "query",
          durationMs = 1500,
          durationApiMs = 800,
          isError = false,
          numTurns = 1,
          sessionId = "test-session-123",
          totalCostUsd = Some(0.001),
          usage = Some(Map("input_tokens" -> 10, "output_tokens" -> 1)),
          result = Some("4")
        )
      )

  test("query with simple prompt returns assistant message and result"):
    val options = QueryOptions(prompt = "What is 2+2?")
    
    MockClaudeCode.query(options)
      .compile
      .toList
      .map: messages =>
        assertEquals(messages.length, 2)
        
        // Verify first message is AssistantMessage
        messages.head match
          case AssistantMessage(content) =>
            assertEquals(content.length, 1)
            content.head match
              case TextBlock(text) => assertEquals(text, "4")
              case _ => fail("Expected TextBlock")
          case _ => fail("Expected AssistantMessage")
        
        // Verify second message is ResultMessage  
        messages(1) match
          case result: ResultMessage =>
            assertEquals(result.subtype, "query")
            assertEquals(result.isError, false)
            assertEquals(result.numTurns, 1)
            assertEquals(result.sessionId, "test-session-123")
            assertEquals(result.totalCostUsd, Some(0.001))
            assert(result.usage.isDefined)
          case _ => fail("Expected ResultMessage")

  test("query options are properly constructed with defaults"):
    val options = QueryOptions(prompt = "Test prompt")
    
    assertEquals(options.prompt, "Test prompt")
    assertEquals(options.cwd, None)
    assertEquals(options.maxTurns, None)
    assertEquals(options.allowedTools, None)
    assertEquals(options.systemPrompt, None)
    assertEquals(options.permissionMode, None)

  test("query options can be configured with custom values"):
    val options = QueryOptions(
      prompt = "Complex query",
      cwd = Some("/custom/path"),
      maxTurns = Some(5),
      allowedTools = Some(List("bash", "read")),
      systemPrompt = Some("You are a helpful assistant"),
      permissionMode = Some(PermissionMode.AcceptEdits)
    )
    
    assertEquals(options.prompt, "Complex query")
    assertEquals(options.cwd, Some("/custom/path"))
    assertEquals(options.maxTurns, Some(5))
    assertEquals(options.allowedTools, Some(List("bash", "read")))
    assertEquals(options.systemPrompt, Some("You are a helpful assistant"))
    assertEquals(options.permissionMode, Some(PermissionMode.AcceptEdits))

  test("query stream can handle tool use messages"):
    val toolUseMessage = AssistantMessage(List(
      TextBlock("I'll help you list files."),
      ToolUseBlock(
        id = "tool_123",
        name = "bash",
        input = Map("command" -> "ls -la")
      )
    ))
    
    val toolResultMessage = AssistantMessage(List(
      ToolResultBlock(
        toolUseId = "tool_123",
        content = Some("file1.txt\nfile2.txt"),
        isError = Some(false)
      ),
      TextBlock("Here are the files in the directory.")
    ))

    // Verify tool use message structure
    assertEquals(toolUseMessage.content.length, 2)
    toolUseMessage.content(1) match
      case ToolUseBlock(id, name, input) =>
        assertEquals(id, "tool_123")
        assertEquals(name, "bash")
        assertEquals(input("command"), "ls -la")
      case _ => fail("Expected ToolUseBlock")

    // Verify tool result message structure  
    toolResultMessage.content(0) match
      case ToolResultBlock(toolUseId, content, isError) =>
        assertEquals(toolUseId, "tool_123")
        assertEquals(content, Some("file1.txt\nfile2.txt"))
        assertEquals(isError, Some(false))
      case _ => fail("Expected ToolResultBlock")