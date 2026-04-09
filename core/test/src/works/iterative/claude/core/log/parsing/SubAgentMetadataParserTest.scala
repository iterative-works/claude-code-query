// PURPOSE: Unit tests for SubAgentMetadataParser JSON parsing functionality
// PURPOSE: Verifies correct parsing of .meta.json sidecar files into SubAgentMetadata

package works.iterative.claude.core.log.parsing

import munit.FunSuite
import io.circe.parser

class SubAgentMetadataParserTest extends FunSuite:

  private val transcriptPath = os.Path("/tmp/subagents/agent-abc/transcript.jsonl")

  test("valid JSON with all fields returns Some(SubAgentMetadata)"):
    val json = parser
      .parse("""{
        "agentId": "agent-abc-123",
        "agentType": "coder",
        "description": "Writes Scala code"
      }""")
      .getOrElse(fail("parse failed"))
    val result = SubAgentMetadataParser.parse(json, transcriptPath)
    result match
      case Some(meta) =>
        assertEquals(meta.agentId, "agent-abc-123")
        assertEquals(meta.agentType, Some("coder"))
        assertEquals(meta.description, Some("Writes Scala code"))
        assertEquals(meta.transcriptPath, transcriptPath)
      case None => fail("Expected Some(SubAgentMetadata)")

  test("JSON with only required agentId returns Some with None optional fields"):
    val json = parser
      .parse("""{"agentId": "agent-xyz"}""")
      .getOrElse(fail("parse failed"))
    val result = SubAgentMetadataParser.parse(json, transcriptPath)
    result match
      case Some(meta) =>
        assertEquals(meta.agentId, "agent-xyz")
        assertEquals(meta.agentType, None)
        assertEquals(meta.description, None)
        assertEquals(meta.transcriptPath, transcriptPath)
      case None => fail("Expected Some(SubAgentMetadata)")

  test("JSON missing required agentId returns None"):
    val json = parser
      .parse("""{"agentType": "coder", "description": "Does stuff"}""")
      .getOrElse(fail("parse failed"))
    val result = SubAgentMetadataParser.parse(json, transcriptPath)
    assertEquals(result, None)

  test("empty JSON object returns None"):
    val json = parser
      .parse("""{}""")
      .getOrElse(fail("parse failed"))
    val result = SubAgentMetadataParser.parse(json, transcriptPath)
    assertEquals(result, None)

  test("JSON null returns None"):
    val result = SubAgentMetadataParser.parse(io.circe.Json.Null, transcriptPath)
    assertEquals(result, None)

  test("transcriptPath is stored in parsed SubAgentMetadata"):
    val customPath = os.Path("/home/user/.claude/projects/session/subagents/agent-1/transcript.jsonl")
    val json = parser
      .parse("""{"agentId": "agent-1"}""")
      .getOrElse(fail("parse failed"))
    val result = SubAgentMetadataParser.parse(json, customPath)
    result match
      case Some(meta) => assertEquals(meta.transcriptPath, customPath)
      case None       => fail("Expected Some(SubAgentMetadata)")
