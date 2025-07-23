// PURPOSE: Tests for direct-style JSON parsing functionality
// PURPOSE: Verifies JSON parsing without IO effects, returning Either results
package works.iterative.claude.direct.internal.parsing

import works.iterative.claude.core.{JsonParsingError}
import works.iterative.claude.core.model.*
import works.iterative.claude.direct.internal.parsing.JsonParser

class JsonParserTest extends munit.FunSuite:

  test("T3.1: parseJsonLineWithContext handles valid JSON messages") {
    // Setup: Valid JSON message strings from CLI output
    val validSystemMessage =
      """{"type":"system","subtype":"user_context","context_user_id":"user_01JHD7Y82DBTRS66XHKZ1CKZH4"}"""
    val validUserMessage = """{"type":"user","content":"Hello Claude!"}"""
    val validAssistantMessage =
      """{"type":"assistant","message":{"content":[{"type":"text","text":"Hello! How can I help you today?"}]}}"""
    val validResultMessage =
      """{"type":"result","subtype":"conversation_result","duration_ms":1234,"duration_api_ms":567,"is_error":false,"num_turns":1,"session_id":"session_123"}"""

    // Execute: Parse valid JSON messages with line context
    val systemResult =
      JsonParser.parseJsonLineWithContext(validSystemMessage, 1)
    val userResult = JsonParser.parseJsonLineWithContext(validUserMessage, 2)
    val assistantResult =
      JsonParser.parseJsonLineWithContext(validAssistantMessage, 3)
    val resultResult =
      JsonParser.parseJsonLineWithContext(validResultMessage, 4)

    // Verify: Should return Right with parsed Message objects
    systemResult match
      case Right(Some(SystemMessage(subtype, data))) =>
        assertEquals(subtype, "user_context")
        assert(data.contains("context_user_id"))
      case other =>
        fail(s"Expected Right(Some(SystemMessage(...))) but got: $other")

    userResult match
      case Right(Some(UserMessage(content))) =>
        assertEquals(content, "Hello Claude!")
      case other =>
        fail(s"Expected Right(Some(UserMessage(...))) but got: $other")

    assistantResult match
      case Right(Some(AssistantMessage(content))) =>
        assertEquals(content.length, 1)
        content.head match
          case TextBlock(text) =>
            assertEquals(text, "Hello! How can I help you today?")
          case other => fail(s"Expected TextBlock but got: $other")
      case other =>
        fail(s"Expected Right(Some(AssistantMessage(...))) but got: $other")

    resultResult match
      case Right(
            Some(
              ResultMessage(
                subtype,
                durationMs,
                durationApiMs,
                isError,
                numTurns,
                sessionId,
                _,
                _,
                _
              )
            )
          ) =>
        assertEquals(subtype, "conversation_result")
        assertEquals(durationMs, 1234)
        assertEquals(durationApiMs, 567)
        assertEquals(isError, false)
        assertEquals(numTurns, 1)
        assertEquals(sessionId, "session_123")
      case other =>
        fail(s"Expected Right(Some(ResultMessage(...))) but got: $other")
  }

  test("T3.2: parseJsonLineWithContext handles empty lines gracefully") {
    // Setup: Empty and whitespace-only strings
    val emptyLine = ""
    val whitespaceLine = "   \t  \n  "
    val justSpaces = "     "

    // Execute: Parse empty lines with line context
    val emptyResult = JsonParser.parseJsonLineWithContext(emptyLine, 1)
    val whitespaceResult =
      JsonParser.parseJsonLineWithContext(whitespaceLine, 2)
    val spacesResult = JsonParser.parseJsonLineWithContext(justSpaces, 3)

    // Verify: Should return Right(None) for empty lines
    assertEquals(emptyResult, Right(None))
    assertEquals(whitespaceResult, Right(None))
    assertEquals(spacesResult, Right(None))
  }

  test("T3.3: parseJsonLineWithContext handles malformed JSON gracefully") {
    // Setup: Invalid JSON strings with context
    val malformedJson1 = """{"type":"system","missing_quote:true}"""
    val malformedJson2 = """{"type":"user","content":"Hello" extra_text}"""
    val malformedJson3 = """{"type":"assistant",}"""
    val notJsonAtAll = """This is not JSON at all!"""

    // Execute: Parse malformed JSON with line context
    val result1 = JsonParser.parseJsonLineWithContext(malformedJson1, 5)
    val result2 = JsonParser.parseJsonLineWithContext(malformedJson2, 10)
    val result3 = JsonParser.parseJsonLineWithContext(malformedJson3, 15)
    val result4 = JsonParser.parseJsonLineWithContext(notJsonAtAll, 20)

    // Verify: Should return Left(JsonParsingError) with line context
    result1 match
      case Left(JsonParsingError(line, lineNumber, cause)) =>
        assertEquals(line, malformedJson1)
        assertEquals(lineNumber, 5)
        assert(cause != null)
      case other =>
        fail(s"Expected Left(JsonParsingError(...)) but got: $other")

    result2 match
      case Left(JsonParsingError(line, lineNumber, cause)) =>
        assertEquals(line, malformedJson2)
        assertEquals(lineNumber, 10)
        assert(cause != null)
      case other =>
        fail(s"Expected Left(JsonParsingError(...)) but got: $other")

    result3 match
      case Left(JsonParsingError(line, lineNumber, cause)) =>
        assertEquals(line, malformedJson3)
        assertEquals(lineNumber, 15)
        assert(cause != null)
      case other =>
        fail(s"Expected Left(JsonParsingError(...)) but got: $other")

    result4 match
      case Left(JsonParsingError(line, lineNumber, cause)) =>
        assertEquals(line, notJsonAtAll)
        assertEquals(lineNumber, 20)
        assert(cause != null)
      case other =>
        fail(s"Expected Left(JsonParsingError(...)) but got: $other")
  }
