package works.iterative.claude.core.log.parsing

// PURPOSE: Unit tests for ConversationLogParser log entry parsing functionality
// PURPOSE: Verifies correct parsing of all log entry types, envelope metadata, and error handling

import munit.FunSuite
import io.circe.parser
import java.time.Instant
import works.iterative.claude.core.log.model.*
import works.iterative.claude.core.model.*

class ConversationLogParserTest extends FunSuite:

  // --- Entry point tests ---

  test("parseLogLine with valid JSONL line returns Some(ConversationLogEntry)"):
    val line =
      """{"type":"human","uuid":"u1","sessionId":"s1","message":{"content":"hello"}}"""
    val result = ConversationLogParser.parseLogLine(line)
    assert(
      result.isDefined,
      "Expected Some(ConversationLogEntry) for valid JSONL"
    )

  test("parseLogLine with empty line returns None"):
    assertEquals(ConversationLogParser.parseLogLine(""), None)

  test("parseLogLine with whitespace-only line returns None"):
    assertEquals(ConversationLogParser.parseLogLine("   \t  "), None)

  test("parseLogLine with invalid JSON returns None"):
    assertEquals(
      ConversationLogParser.parseLogLine("""{"type": "human", malformed}"""),
      None
    )

  test("parseLogEntry with missing required uuid field returns None"):
    val json = parser
      .parse("""{"type":"human","sessionId":"s1","message":{"content":"hi"}}""")
      .getOrElse(fail("parse failed"))
    assertEquals(ConversationLogParser.parseLogEntry(json), None)

  test("parseLogEntry with missing required sessionId field returns None"):
    val json = parser
      .parse("""{"type":"human","uuid":"u1","message":{"content":"hi"}}""")
      .getOrElse(fail("parse failed"))
    assertEquals(ConversationLogParser.parseLogEntry(json), None)

  // --- Envelope metadata tests ---

  test("parses all envelope metadata fields"):
    val line =
      """{
        "type":"human",
        "uuid":"uuid-001",
        "parentUuid":"parent-uuid-000",
        "timestamp":"2024-01-15T10:30:00Z",
        "sessionId":"session-abc",
        "isSidechain":true,
        "cwd":"/home/user/project",
        "version":"1.2.3",
        "message":{"content":"hello"}
      }"""
    val result = ConversationLogParser.parseLogLine(line)
    result match
      case Some(entry) =>
        assertEquals(entry.uuid, "uuid-001")
        assertEquals(entry.parentUuid, Some("parent-uuid-000"))
        assertEquals(
          entry.timestamp,
          Some(Instant.parse("2024-01-15T10:30:00Z"))
        )
        assertEquals(entry.sessionId, "session-abc")
        assertEquals(entry.isSidechain, true)
        assertEquals(entry.cwd, Some("/home/user/project"))
        assertEquals(entry.version, Some("1.2.3"))
      case None => fail("Expected Some(ConversationLogEntry)")

  test("parses with optional fields absent"):
    val line =
      """{"type":"human","uuid":"u2","sessionId":"s2","message":{"content":"hi"}}"""
    val result = ConversationLogParser.parseLogLine(line)
    result match
      case Some(entry) =>
        assertEquals(entry.parentUuid, None)
        assertEquals(entry.timestamp, None)
        assertEquals(entry.cwd, None)
        assertEquals(entry.version, None)
      case None => fail("Expected Some(ConversationLogEntry)")

  test("parses ISO-8601 timestamp string to Instant"):
    val line =
      """{
        "type":"human",
        "uuid":"u3",
        "sessionId":"s3",
        "timestamp":"2025-06-01T12:00:00.000Z",
        "message":{"content":"ts test"}
      }"""
    val result = ConversationLogParser.parseLogLine(line)
    result match
      case Some(entry) =>
        assertEquals(
          entry.timestamp,
          Some(Instant.parse("2025-06-01T12:00:00.000Z"))
        )
      case None => fail("Expected Some(ConversationLogEntry)")

  test("isSidechain defaults to false when absent"):
    val line =
      """{"type":"human","uuid":"u4","sessionId":"s4","message":{"content":"x"}}"""
    val result = ConversationLogParser.parseLogLine(line)
    result match
      case Some(entry) => assertEquals(entry.isSidechain, false)
      case None        => fail("Expected Some(ConversationLogEntry)")

  // --- Payload type tests ---

  test(
    """"human" type with string content produces UserLogEntry with List(TextBlock)"""
  ):
    val line =
      """{"type":"human","uuid":"u5","sessionId":"s5","message":{"content":"hello world"}}"""
    val result = ConversationLogParser.parseLogLine(line)
    result match
      case Some(
            ConversationLogEntry(_, _, _, _, _, _, _, UserLogEntry(content))
          ) =>
        assertEquals(content, List(TextBlock("hello world")))
      case Some(entry) => fail(s"Expected UserLogEntry, got: ${entry.payload}")
      case None        => fail("Expected Some(ConversationLogEntry)")

  test(
    """"human" type with array content produces UserLogEntry with parsed content blocks"""
  ):
    val line =
      """{
        "type":"human",
        "uuid":"u6",
        "sessionId":"s6",
        "message":{
          "content":[
            {"type":"text","text":"first block"},
            {"type":"text","text":"second block"}
          ]
        }
      }"""
    val result = ConversationLogParser.parseLogLine(line)
    result match
      case Some(
            ConversationLogEntry(_, _, _, _, _, _, _, UserLogEntry(content))
          ) =>
        assertEquals(
          content,
          List(TextBlock("first block"), TextBlock("second block"))
        )
      case Some(entry) => fail(s"Expected UserLogEntry, got: ${entry.payload}")
      case None        => fail("Expected Some(ConversationLogEntry)")

  test(
    """"assistant" type with content, model, usage, requestId produces AssistantLogEntry"""
  ):
    val line =
      """{
        "type":"assistant",
        "uuid":"u7",
        "sessionId":"s7",
        "requestId":"req-999",
        "message":{
          "model":"claude-3-5-sonnet-20241022",
          "content":[{"type":"text","text":"response text"}],
          "usage":{
            "input_tokens":100,
            "output_tokens":50,
            "cache_creation_input_tokens":10,
            "cache_read_input_tokens":5,
            "service_tier":"standard"
          }
        }
      }"""
    val result = ConversationLogParser.parseLogLine(line)
    result match
      case Some(
            ConversationLogEntry(
              _,
              _,
              _,
              _,
              _,
              _,
              _,
              AssistantLogEntry(content, model, usage, requestId)
            )
          ) =>
        assertEquals(content, List(TextBlock("response text")))
        assertEquals(model, Some("claude-3-5-sonnet-20241022"))
        assertEquals(requestId, Some("req-999"))
        usage match
          case Some(u) =>
            assertEquals(u.inputTokens, 100)
            assertEquals(u.outputTokens, 50)
            assertEquals(u.cacheCreationInputTokens, Some(10))
            assertEquals(u.cacheReadInputTokens, Some(5))
            assertEquals(u.serviceTier, Some("standard"))
          case None => fail("Expected Some(TokenUsage)")
      case Some(entry) =>
        fail(s"Expected AssistantLogEntry, got: ${entry.payload}")
      case None => fail("Expected Some(ConversationLogEntry)")

  test(""""assistant" type with minimal fields produces AssistantLogEntry"""):
    val line =
      """{
        "type":"assistant",
        "uuid":"u8",
        "sessionId":"s8",
        "message":{
          "content":[{"type":"text","text":"minimal"}]
        }
      }"""
    val result = ConversationLogParser.parseLogLine(line)
    result match
      case Some(
            ConversationLogEntry(
              _,
              _,
              _,
              _,
              _,
              _,
              _,
              AssistantLogEntry(content, model, usage, requestId)
            )
          ) =>
        assertEquals(content, List(TextBlock("minimal")))
        assertEquals(model, None)
        assertEquals(usage, None)
        assertEquals(requestId, None)
      case Some(entry) =>
        fail(s"Expected AssistantLogEntry, got: ${entry.payload}")
      case None => fail("Expected Some(ConversationLogEntry)")

  test(""""system" type with subtype and data produces SystemLogEntry"""):
    val line =
      """{
        "type":"system",
        "uuid":"u9",
        "sessionId":"s9",
        "subtype":"init",
        "apiKeySource":"environment",
        "toolCount":5
      }"""
    val result = ConversationLogParser.parseLogLine(line)
    result match
      case Some(
            ConversationLogEntry(
              _,
              _,
              _,
              _,
              _,
              _,
              _,
              SystemLogEntry(subtype, data)
            )
          ) =>
        assertEquals(subtype, "init")
        assertEquals(data("apiKeySource"), "environment")
      case Some(entry) =>
        fail(s"Expected SystemLogEntry, got: ${entry.payload}")
      case None => fail("Expected Some(ConversationLogEntry)")

  test(
    """"progress" type with data and parentToolUseId produces ProgressLogEntry"""
  ):
    val line =
      """{
        "type":"progress",
        "uuid":"u10",
        "sessionId":"s10",
        "parentToolUseId":"tool-use-123",
        "progress":0.75
      }"""
    val result = ConversationLogParser.parseLogLine(line)
    result match
      case Some(
            ConversationLogEntry(
              _,
              _,
              _,
              _,
              _,
              _,
              _,
              ProgressLogEntry(data, parentToolUseId)
            )
          ) =>
        assertEquals(parentToolUseId, Some("tool-use-123"))
        assertEquals(data("progress"), 0.75)
      case Some(entry) =>
        fail(s"Expected ProgressLogEntry, got: ${entry.payload}")
      case None => fail("Expected Some(ConversationLogEntry)")

  test(
    """"queue_operation" type with operation and content produces QueueOperationLogEntry"""
  ):
    val line =
      """{
        "type":"queue_operation",
        "uuid":"u11",
        "sessionId":"s11",
        "operation":"enqueue",
        "content":"some message"
      }"""
    val result = ConversationLogParser.parseLogLine(line)
    result match
      case Some(
            ConversationLogEntry(
              _,
              _,
              _,
              _,
              _,
              _,
              _,
              QueueOperationLogEntry(operation, content)
            )
          ) =>
        assertEquals(operation, "enqueue")
        assertEquals(content, Some("some message"))
      case Some(entry) =>
        fail(s"Expected QueueOperationLogEntry, got: ${entry.payload}")
      case None => fail("Expected Some(ConversationLogEntry)")

  test(
    """"file_history_snapshot" type with data produces FileHistorySnapshotLogEntry"""
  ):
    val line =
      """{
        "type":"file_history_snapshot",
        "uuid":"u12",
        "sessionId":"s12",
        "files":["/a.txt","/b.txt"]
      }"""
    val result = ConversationLogParser.parseLogLine(line)
    result match
      case Some(
            ConversationLogEntry(
              _,
              _,
              _,
              _,
              _,
              _,
              _,
              FileHistorySnapshotLogEntry(data)
            )
          ) =>
        assertEquals(data("files"), List("/a.txt", "/b.txt"))
      case Some(entry) =>
        fail(s"Expected FileHistorySnapshotLogEntry, got: ${entry.payload}")
      case None => fail("Expected Some(ConversationLogEntry)")

  test(""""last_prompt" type with data produces LastPromptLogEntry"""):
    val line =
      """{
        "type":"last_prompt",
        "uuid":"u13",
        "sessionId":"s13",
        "promptText":"what is 2+2?"
      }"""
    val result = ConversationLogParser.parseLogLine(line)
    result match
      case Some(
            ConversationLogEntry(_, _, _, _, _, _, _, LastPromptLogEntry(data))
          ) =>
        assertEquals(data("promptText"), "what is 2+2?")
      case Some(entry) =>
        fail(s"Expected LastPromptLogEntry, got: ${entry.payload}")
      case None => fail("Expected Some(ConversationLogEntry)")

  test("unknown type produces RawLogEntry with preserved JSON"):
    val line =
      """{
        "type":"future_type_v99",
        "uuid":"u14",
        "sessionId":"s14",
        "someField":"someValue"
      }"""
    val result = ConversationLogParser.parseLogLine(line)
    result match
      case Some(
            ConversationLogEntry(
              _,
              _,
              _,
              _,
              _,
              _,
              _,
              RawLogEntry(entryType, json)
            )
          ) =>
        assertEquals(entryType, "future_type_v99")
        assert(json.isObject, "Expected preserved JSON object")
      case Some(entry) => fail(s"Expected RawLogEntry, got: ${entry.payload}")
      case None        => fail("Expected Some(ConversationLogEntry)")

  // --- TokenUsage tests ---

  test("parses full token usage (all fields including cache and service_tier)"):
    val line =
      """{
        "type":"assistant",
        "uuid":"u15",
        "sessionId":"s15",
        "message":{
          "content":[],
          "usage":{
            "input_tokens":200,
            "output_tokens":80,
            "cache_creation_input_tokens":30,
            "cache_read_input_tokens":15,
            "service_tier":"standard"
          }
        }
      }"""
    val result = ConversationLogParser.parseLogLine(line)
    result match
      case Some(
            ConversationLogEntry(
              _,
              _,
              _,
              _,
              _,
              _,
              _,
              AssistantLogEntry(_, _, Some(usage), _)
            )
          ) =>
        assertEquals(usage.inputTokens, 200)
        assertEquals(usage.outputTokens, 80)
        assertEquals(usage.cacheCreationInputTokens, Some(30))
        assertEquals(usage.cacheReadInputTokens, Some(15))
        assertEquals(usage.serviceTier, Some("standard"))
      case Some(entry) =>
        fail(s"Expected AssistantLogEntry with usage, got: ${entry.payload}")
      case None => fail("Expected Some(ConversationLogEntry)")

  test("parses minimal token usage (only input_tokens and output_tokens)"):
    val line =
      """{
        "type":"assistant",
        "uuid":"u16",
        "sessionId":"s16",
        "message":{
          "content":[],
          "usage":{
            "input_tokens":10,
            "output_tokens":5
          }
        }
      }"""
    val result = ConversationLogParser.parseLogLine(line)
    result match
      case Some(
            ConversationLogEntry(
              _,
              _,
              _,
              _,
              _,
              _,
              _,
              AssistantLogEntry(_, _, Some(usage), _)
            )
          ) =>
        assertEquals(usage.inputTokens, 10)
        assertEquals(usage.outputTokens, 5)
        assertEquals(usage.cacheCreationInputTokens, None)
        assertEquals(usage.cacheReadInputTokens, None)
        assertEquals(usage.serviceTier, None)
      case Some(entry) =>
        fail(s"Expected AssistantLogEntry with usage, got: ${entry.payload}")
      case None => fail("Expected Some(ConversationLogEntry)")

  // --- Error path tests ---

  test("\"system\" type without subtype returns None"):
    val line =
      """{"type":"system","uuid":"u20","sessionId":"s20","apiKeySource":"env"}"""
    val result = ConversationLogParser.parseLogLine(line)
    assertEquals(result, None)

  test("\"queue_operation\" type without operation returns None"):
    val line =
      """{"type":"queue_operation","uuid":"u21","sessionId":"s21","content":"msg"}"""
    val result = ConversationLogParser.parseLogLine(line)
    assertEquals(result, None)

  test("\"human\" type without message field returns None"):
    val line =
      """{"type":"human","uuid":"u22","sessionId":"s22"}"""
    val result = ConversationLogParser.parseLogLine(line)
    assertEquals(result, None)

  test("\"assistant\" type without message field returns None"):
    val line =
      """{"type":"assistant","uuid":"u23","sessionId":"s23"}"""
    val result = ConversationLogParser.parseLogLine(line)
    assertEquals(result, None)

  test("parseLogEntry with missing required type field returns None"):
    val json = parser
      .parse("""{"uuid":"u24","sessionId":"s24","message":{"content":"hi"}}""")
      .getOrElse(fail("parse failed"))
    assertEquals(ConversationLogParser.parseLogEntry(json), None)

  test("malformed timestamp string results in None timestamp"):
    val line =
      """{
        "type":"human",
        "uuid":"u25",
        "sessionId":"s25",
        "timestamp":"not-a-date",
        "message":{"content":"hi"}
      }"""
    val result = ConversationLogParser.parseLogLine(line)
    result match
      case Some(entry) => assertEquals(entry.timestamp, None)
      case None        => fail("Expected Some(ConversationLogEntry)")
