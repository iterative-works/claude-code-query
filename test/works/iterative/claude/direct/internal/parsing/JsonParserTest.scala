// PURPOSE: Tests for direct-style JSON parsing functionality
// PURPOSE: Verifies JSON parsing without IO effects, returning Either results
package works.iterative.claude.direct.internal.parsing

import works.iterative.claude.core.{JsonParsingError}
import works.iterative.claude.core.model.*
import works.iterative.claude.direct.internal.parsing.JsonParser

class JsonParserTest extends munit.FunSuite:

  test("T3.1: parseJsonLineWithContext handles valid JSON messages") {
    // Setup: Valid JSON message strings from CLI output
    val validSystemMessage = """{"type":"system","subtype":"user_context","context_user_id":"user_01JHD7Y82DBTRS66XHKZ1CKZH4"}"""
    val validUserMessage = """{"type":"user","content":"Hello Claude!"}"""
    val validAssistantMessage = """{"type":"assistant","message":{"content":[{"type":"text","text":"Hello! How can I help you today?"}]}}"""
    val validResultMessage = """{"type":"result","subtype":"conversation_result","duration_ms":1234,"duration_api_ms":567,"is_error":false,"num_turns":1,"session_id":"session_123"}"""

    // Execute: Parse valid JSON messages with line context
    val systemResult = JsonParser.parseJsonLineWithContext(validSystemMessage, 1)
    val userResult = JsonParser.parseJsonLineWithContext(validUserMessage, 2)
    val assistantResult = JsonParser.parseJsonLineWithContext(validAssistantMessage, 3)
    val resultResult = JsonParser.parseJsonLineWithContext(validResultMessage, 4)

    // Verify: Should return Right with parsed Message objects
    systemResult match
      case Right(Some(SystemMessage(subtype, data))) =>
        assertEquals(subtype, "user_context")
        assert(data.contains("context_user_id"))
      case other => fail(s"Expected Right(Some(SystemMessage(...))) but got: $other")

    userResult match
      case Right(Some(UserMessage(content))) =>
        assertEquals(content, "Hello Claude!")
      case other => fail(s"Expected Right(Some(UserMessage(...))) but got: $other")

    assistantResult match
      case Right(Some(AssistantMessage(content))) =>
        assertEquals(content.length, 1)
        content.head match
          case TextBlock(text) => assertEquals(text, "Hello! How can I help you today?")
          case other => fail(s"Expected TextBlock but got: $other")
      case other => fail(s"Expected Right(Some(AssistantMessage(...))) but got: $other")

    resultResult match
      case Right(Some(ResultMessage(subtype, durationMs, durationApiMs, isError, numTurns, sessionId, _, _, _))) =>
        assertEquals(subtype, "conversation_result")
        assertEquals(durationMs, 1234)
        assertEquals(durationApiMs, 567)
        assertEquals(isError, false)
        assertEquals(numTurns, 1)
        assertEquals(sessionId, "session_123")
      case other => fail(s"Expected Right(Some(ResultMessage(...))) but got: $other")
  }