package works.iterative.claude.core.model

// PURPOSE: Unit tests for ContentBlock sealed trait and all its variants
// PURPOSE: Verifies ThinkingBlock and RedactedThinkingBlock are valid ContentBlock instances

import munit.FunSuite

class ContentBlockTest extends FunSuite:

  test("ThinkingBlock should be a ContentBlock"):
    val block: ContentBlock =
      ThinkingBlock("some thinking content", "sig-abc123")
    block match
      case ThinkingBlock(thinking, signature) =>
        assertEquals(thinking, "some thinking content")
        assertEquals(signature, "sig-abc123")
      case _ => fail("Expected ThinkingBlock")

  test("RedactedThinkingBlock should be a ContentBlock"):
    val block: ContentBlock = RedactedThinkingBlock("redacted-data-xyz")
    block match
      case RedactedThinkingBlock(data) =>
        assertEquals(data, "redacted-data-xyz")
      case _ => fail("Expected RedactedThinkingBlock")

  test(
    "ContentBlock variants include TextBlock, ToolUseBlock, ToolResultBlock, ThinkingBlock, RedactedThinkingBlock"
  ):
    val blocks: List[ContentBlock] = List(
      TextBlock("hello"),
      ToolUseBlock("id-1", "bash", Map.empty),
      ToolResultBlock("id-1", Some("result"), None),
      ThinkingBlock("thinking", "sig"),
      RedactedThinkingBlock("redacted")
    )
    assertEquals(blocks.size, 5)

  test("ThinkingBlock equality is structural"):
    val a = ThinkingBlock("same", "sig")
    val b = ThinkingBlock("same", "sig")
    assertEquals(a, b)

  test("RedactedThinkingBlock equality is structural"):
    val a = RedactedThinkingBlock("data")
    val b = RedactedThinkingBlock("data")
    assertEquals(a, b)
