// PURPOSE: Tests for the ZIO JSON parsing wrapper over core pure parsing logic
// PURPOSE: Verifies typed JsonParsingError on malformed input and successful decoding

package works.iterative.claude.zio.internal.parsing

import zio.*
import zio.test.*
import works.iterative.claude.core.JsonParsingError
import works.iterative.claude.core.model.*
import works.iterative.claude.zio.internal.testing.ClaudeZioSpec

object JsonParserTest extends ClaudeZioSpec:
  def spec = suite("JsonParser")(
    test("returns None for a blank line"):
      for result <- JsonParser.parseJsonLineWithContext("   ", 1)
      yield assertTrue(result.isEmpty),
    test("parses an assistant text message"):
      val line =
        """{"type":"assistant","message":{"content":[{"type":"text","text":"hi"}]}}"""
      for result <- JsonParser.parseJsonLineWithContext(line, 1)
      yield assertTrue(
        result.contains(AssistantMessage(List(TextBlock("hi"))))
      ),
    test("returns None for valid JSON that is not a known message"):
      for result <- JsonParser.parseJsonLineWithContext("""{"foo":"bar"}""", 2)
      yield assertTrue(result.isEmpty),
    test("fails with JsonParsingError carrying line context on malformed JSON"):
      for error <- JsonParser.parseJsonLineWithContext("{ not json", 5).flip
      yield assertTrue(error.lineNumber == 5, error.line == "{ not json"),
    test("parseJsonLine never fails and returns the parsed message"):
      val line =
        """{"type":"assistant","message":{"content":[{"type":"text","text":"yo"}]}}"""
      for result <- JsonParser.parseJsonLine(line)
      yield assertTrue(result.contains(AssistantMessage(List(TextBlock("yo"))))),
    test("parseJsonLine returns None without failing on malformed JSON"):
      for result <- JsonParser.parseJsonLine("{ not json")
      yield assertTrue(result.isEmpty)
  )
