// PURPOSE: Unit tests for SubAgentMetadataParser JSON parsing functionality
// PURPOSE: Verifies correct parsing of .meta.json sidecar files into SubAgentMetadata

package works.iterative.claude.core.log.parsing

import munit.FunSuite
import io.circe.parser

class SubAgentMetadataParserTest extends FunSuite:

  private val transcriptPath = os.Path("/tmp/subagents/agent-abc123.jsonl")

  test("JSON with agentType and description returns fully populated SubAgentMetadata"):
    val json = parser
      .parse("""{
        "agentType": "coder",
        "description": "Writes Scala code"
      }""")
      .getOrElse(fail("parse failed"))
    val result = SubAgentMetadataParser.parse(json, transcriptPath)
    result match
      case Some(meta) =>
        assertEquals(meta.agentId, "agent-abc123")
        assertEquals(meta.agentType, Some("coder"))
        assertEquals(meta.description, Some("Writes Scala code"))
        assertEquals(meta.transcriptPath, transcriptPath)
      case None => fail("Expected Some(SubAgentMetadata)")

  test("JSON with no optional fields returns Some with agentId from filename and None optional fields"):
    val json = parser
      .parse("""{}""")
      .getOrElse(fail("parse failed"))
    val result = SubAgentMetadataParser.parse(json, transcriptPath)
    result match
      case Some(meta) =>
        assertEquals(meta.agentId, "agent-abc123")
        assertEquals(meta.agentType, None)
        assertEquals(meta.description, None)
        assertEquals(meta.transcriptPath, transcriptPath)
      case None => fail("Expected Some(SubAgentMetadata)")

  test("JSON with only agentType and description returns Some"):
    val json = parser
      .parse("""{"agentType": "coder", "description": "Does stuff"}""")
      .getOrElse(fail("parse failed"))
    val result = SubAgentMetadataParser.parse(json, transcriptPath)
    result match
      case Some(meta) =>
        assertEquals(meta.agentId, "agent-abc123")
        assertEquals(meta.agentType, Some("coder"))
        assertEquals(meta.description, Some("Does stuff"))
      case None => fail("Expected Some(SubAgentMetadata)")

  test("JSON null returns None"):
    val result = SubAgentMetadataParser.parse(io.circe.Json.Null, transcriptPath)
    assertEquals(result, None)

  test("transcriptPath is stored in parsed SubAgentMetadata"):
    val customPath = os.Path("/home/user/.claude/projects/session/subagents/agent-xyz789.jsonl")
    val json = parser
      .parse("""{}""")
      .getOrElse(fail("parse failed"))
    val result = SubAgentMetadataParser.parse(json, customPath)
    result match
      case Some(meta) => assertEquals(meta.transcriptPath, customPath)
      case None       => fail("Expected Some(SubAgentMetadata)")

  test("agentId is derived from transcript filename, not from JSON"):
    val pathWithId = os.Path("/tmp/agent-derived-id.jsonl")
    val json = parser
      .parse("""{"agentType": "coder"}""")
      .getOrElse(fail("parse failed"))
    val result = SubAgentMetadataParser.parse(json, pathWithId)
    result match
      case Some(meta) => assertEquals(meta.agentId, "agent-derived-id")
      case None       => fail("Expected Some(SubAgentMetadata)")

  test("agentId in JSON is ignored; filename is used instead"):
    val json = parser
      .parse("""{"agentId": "should-be-ignored", "agentType": "coder"}""")
      .getOrElse(fail("parse failed"))
    val result = SubAgentMetadataParser.parse(json, transcriptPath)
    result match
      case Some(meta) => assertEquals(meta.agentId, "agent-abc123")
      case None       => fail("Expected Some(SubAgentMetadata)")

  test("real-world .meta.json content without agentId returns Some with agentId from filename"):
    val realWorldPath = os.Path("/tmp/subagents/agent-a27a237ab9050d9ef.jsonl")
    val json = parser
      .parse("""{"agentType":"iterative-works:code-reviewer","description":"Review security of phase 07"}""")
      .getOrElse(fail("parse failed"))
    val result = SubAgentMetadataParser.parse(json, realWorldPath)
    result match
      case Some(meta) =>
        assertEquals(meta.agentId, "agent-a27a237ab9050d9ef")
        assertEquals(meta.agentType, Some("iterative-works:code-reviewer"))
        assertEquals(meta.description, Some("Review security of phase 07"))
      case None => fail("Expected Some(SubAgentMetadata) for real-world .meta.json content")
