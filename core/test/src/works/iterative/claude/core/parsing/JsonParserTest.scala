package works.iterative.claude.core.parsing

// PURPOSE: Unit tests for JsonParser message parsing functionality
// PURPOSE: Verifies correct parsing of all message types from Claude CLI JSON output

import munit.FunSuite
import io.circe.parser
import works.iterative.claude.core.model.*

class JsonParserTest extends FunSuite:

  test("parseMessage should parse system initialization message"):
    val systemJsonStr = """{
      "type": "system",
      "subtype": "init",
      "apiKeySource": "environment",
      "cwd": "/test",
      "session_id": "test-session-123",
      "tools": ["Read", "Write", "Bash"],
      "mcp_servers": [],
      "model": "claude-3-5-sonnet-20241022",
      "permissionMode": "default"
    }"""

    val json =
      parser.parse(systemJsonStr).getOrElse(fail("Failed to parse JSON"))
    val result = JsonParser.parseMessage(json)

    result match
      case Some(SystemMessage(subtype, data)) =>
        assertEquals(subtype, "init")
        assertEquals(data("apiKeySource"), "environment")
        assertEquals(data("cwd"), "/test")
        assertEquals(data("session_id"), "test-session-123")
        assertEquals(data("model"), "claude-3-5-sonnet-20241022")
        assertEquals(data("permissionMode"), "default")
        assert(data.contains("tools"))
        assert(data.contains("mcp_servers"))
      case Some(other) =>
        fail(s"Expected SystemMessage, got: ${other.getClass.getSimpleName}")
      case None => fail("Expected SystemMessage, got None")

  test("parseMessage should handle existing assistant message"):
    val assistantJsonStr = """{
      "type": "assistant",
      "message": {
        "role": "assistant",
        "content": [{"type": "text", "text": "Hello"}]
      },
      "session_id": "test-session-123"
    }"""

    val json =
      parser.parse(assistantJsonStr).getOrElse(fail("Failed to parse JSON"))
    val result = JsonParser.parseMessage(json)

    result match
      case Some(AssistantMessage(content)) =>
        assertEquals(content.length, 1)
        content.head match
          case TextBlock(text) => assertEquals(text, "Hello")
          case _               => fail("Expected TextBlock")
      case Some(other) =>
        fail(s"Expected AssistantMessage, got: ${other.getClass.getSimpleName}")
      case None => fail("Expected AssistantMessage, got None")

  test("parseMessage should handle existing result message"):
    val resultJsonStr = """{
      "type": "result",
      "subtype": "query",
      "duration_ms": 1500,
      "duration_api_ms": 800,
      "is_error": false,
      "num_turns": 1,
      "result": "4",
      "session_id": "test-session-123",
      "total_cost_usd": 0.001,
      "usage": {"input_tokens": 10, "output_tokens": 1}
    }"""

    val json =
      parser.parse(resultJsonStr).getOrElse(fail("Failed to parse JSON"))
    val result = JsonParser.parseMessage(json)

    result match
      case Some(
            ResultMessage(
              subtype,
              durationMs,
              durationApiMs,
              isError,
              numTurns,
              sessionId,
              totalCostUsd,
              usage,
              resultText
            )
          ) =>
        assertEquals(subtype, "query")
        assertEquals(durationMs, 1500)
        assertEquals(durationApiMs, 800)
        assertEquals(isError, false)
        assertEquals(numTurns, 1)
        assertEquals(sessionId, "test-session-123")
        assertEquals(totalCostUsd, Some(0.001))
        assertEquals(resultText, Some("4"))
        assert(usage.isDefined)
      case Some(other) =>
        fail(s"Expected ResultMessage, got: ${other.getClass.getSimpleName}")
      case None => fail("Expected ResultMessage, got None")

  test("parseMessage should parse user message"):
    val userJsonStr = """{
      "type": "user",
      "content": "What is 2+2?"
    }"""

    val json =
      parser.parse(userJsonStr).getOrElse(fail("Failed to parse JSON"))
    val result = JsonParser.parseMessage(json)

    result match
      case Some(UserMessage(content)) =>
        assertEquals(content, "What is 2+2?")
      case Some(other) =>
        fail(s"Expected UserMessage, got: ${other.getClass.getSimpleName}")
      case None => fail("Expected UserMessage, got None")

  test("parseJsonLine should handle invalid JSON gracefully"):
    val invalidJson = """{"type": "invalid", "malformed": }"""
    val result = JsonParser.parseJsonLine(invalidJson)
    assertEquals(result, None, "Invalid JSON should return None")

  test("parseJsonLine should handle empty lines"):
    val emptyLine = ""
    val result = JsonParser.parseJsonLine(emptyLine)
    assertEquals(result, None, "Empty line should return None")

  test("parseJsonLine should handle whitespace-only lines"):
    val whitespaceLine = "   \t  \n  "
    val result = JsonParser.parseJsonLine(whitespaceLine)
    assertEquals(result, None, "Whitespace-only line should return None")
