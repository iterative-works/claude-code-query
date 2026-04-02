package works.iterative.claude.core.parsing

// PURPOSE: Unit tests for ContentBlockParser covering all five content block types
// PURPOSE: Verifies correct parsing of text, tool_use, tool_result, thinking, and redacted_thinking blocks

import munit.FunSuite
import io.circe.parser
import works.iterative.claude.core.model.*

class ContentBlockParserTest extends FunSuite:

  private def parseJson(jsonStr: String) =
    parser
      .parse(jsonStr)
      .getOrElse(fail(s"Failed to parse test JSON: $jsonStr"))

  test("parse text block returns TextBlock"):
    val json = parseJson("""{"type":"text","text":"Hello world"}""")
    val result = ContentBlockParser.parseContentBlock(json)
    assertEquals(result, Some(TextBlock("Hello world")))

  test("parse tool_use block returns ToolUseBlock"):
    val json = parseJson(
      """{"type":"tool_use","id":"toolu_01","name":"Bash","input":{}}"""
    )
    val result = ContentBlockParser.parseContentBlock(json)
    assertEquals(result, Some(ToolUseBlock("toolu_01", "Bash", Map.empty)))

  test("parse tool_result block returns ToolResultBlock"):
    val json = parseJson(
      """{"type":"tool_result","tool_use_id":"toolu_01","content":"output","is_error":false}"""
    )
    val result = ContentBlockParser.parseContentBlock(json)
    assertEquals(
      result,
      Some(ToolResultBlock("toolu_01", Some("output"), Some(false)))
    )

  test(
    "parse tool_result block with optional fields absent returns ToolResultBlock"
  ):
    val json = parseJson(
      """{"type":"tool_result","tool_use_id":"toolu_02"}"""
    )
    val result = ContentBlockParser.parseContentBlock(json)
    assertEquals(result, Some(ToolResultBlock("toolu_02", None, None)))

  test("parse thinking block returns ThinkingBlock"):
    val json = parseJson(
      """{"type":"thinking","thinking":"I think therefore I am","signature":"sig123"}"""
    )
    val result = ContentBlockParser.parseContentBlock(json)
    assertEquals(
      result,
      Some(ThinkingBlock("I think therefore I am", "sig123"))
    )

  test("parse redacted_thinking block returns RedactedThinkingBlock"):
    val json = parseJson(
      """{"type":"redacted_thinking","data":"opaque_data_blob"}"""
    )
    val result = ContentBlockParser.parseContentBlock(json)
    assertEquals(result, Some(RedactedThinkingBlock("opaque_data_blob")))

  test("parse unknown type returns None"):
    val json =
      parseJson("""{"type":"image","url":"https://example.com/img.png"}""")
    val result = ContentBlockParser.parseContentBlock(json)
    assertEquals(result, None)

  test("parse JSON without type field returns None"):
    val json = parseJson("""{"text":"no type here"}""")
    val result = ContentBlockParser.parseContentBlock(json)
    assertEquals(result, None)
