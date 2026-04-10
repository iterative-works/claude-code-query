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
      """{"type":"user","uuid":"u1","sessionId":"s1","message":{"content":"hello"}}"""
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
      ConversationLogParser.parseLogLine("""{"type": "user", malformed}"""),
      None
    )

  test("parseLogEntry with missing uuid field parses successfully with uuid = None"):
    val json = parser
      .parse("""{"type":"user","sessionId":"s1","message":{"content":"hi"}}""")
      .getOrElse(fail("parse failed"))
    val result = ConversationLogParser.parseLogEntry(json)
    result match
      case Some(entry) => assertEquals(entry.uuid, None)
      case None        => fail("Expected Some(ConversationLogEntry) when uuid is absent")

  test("parseLogEntry with missing required sessionId field returns None"):
    val json = parser
      .parse("""{"type":"user","uuid":"u1","message":{"content":"hi"}}""")
      .getOrElse(fail("parse failed"))
    assertEquals(ConversationLogParser.parseLogEntry(json), None)

  // --- Envelope metadata tests ---

  test("parses all envelope metadata fields"):
    val line =
      """{
        "type":"user",
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
        assertEquals(entry.uuid, Some("uuid-001"))
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
      """{"type":"user","uuid":"u2","sessionId":"s2","message":{"content":"hi"}}"""
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
        "type":"user",
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
      """{"type":"user","uuid":"u4","sessionId":"s4","message":{"content":"x"}}"""
    val result = ConversationLogParser.parseLogLine(line)
    result match
      case Some(entry) => assertEquals(entry.isSidechain, false)
      case None        => fail("Expected Some(ConversationLogEntry)")

  // --- Payload type tests ---

  test(
    """"user" type with string content produces UserLogEntry with List(TextBlock)"""
  ):
    val line =
      """{"type":"user","uuid":"u5","sessionId":"s5","message":{"content":"hello world"}}"""
    val result = ConversationLogParser.parseLogLine(line)
    result match
      case Some(
            ConversationLogEntry(_, _, _, _, _, _, _, UserLogEntry(content), _)
          ) =>
        assertEquals(content, List(TextBlock("hello world")))
      case Some(entry) => fail(s"Expected UserLogEntry, got: ${entry.payload}")
      case None        => fail("Expected Some(ConversationLogEntry)")

  test(
    """"user" type with array content produces UserLogEntry with parsed content blocks"""
  ):
    val line =
      """{
        "type":"user",
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
            ConversationLogEntry(_, _, _, _, _, _, _, UserLogEntry(content), _)
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
              AssistantLogEntry(content, model, usage, requestId),
              _
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
              AssistantLogEntry(content, model, usage, requestId),
              _
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
              SystemLogEntry(subtype, data),
              _
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
              ProgressLogEntry(data, parentToolUseId),
              _
            )
          ) =>
        assertEquals(parentToolUseId, Some("tool-use-123"))
        assertEquals(data("progress"), 0.75)
      case Some(entry) =>
        fail(s"Expected ProgressLogEntry, got: ${entry.payload}")
      case None => fail("Expected Some(ConversationLogEntry)")

  test(
    """"queue-operation" type with operation and content produces QueueOperationLogEntry"""
  ):
    val line =
      """{
        "type":"queue-operation",
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
              QueueOperationLogEntry(operation, content),
              _
            )
          ) =>
        assertEquals(operation, "enqueue")
        assertEquals(content, Some("some message"))
      case Some(entry) =>
        fail(s"Expected QueueOperationLogEntry, got: ${entry.payload}")
      case None => fail("Expected Some(ConversationLogEntry)")

  test(
    """"file-history-snapshot" type with data produces FileHistorySnapshotLogEntry"""
  ):
    val line =
      """{
        "type":"file-history-snapshot",
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
              FileHistorySnapshotLogEntry(data),
              _
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
            ConversationLogEntry(_, _, _, _, _, _, _, LastPromptLogEntry(data), _)
          ) =>
        assertEquals(data("promptText"), "what is 2+2?")
      case Some(entry) =>
        fail(s"Expected LastPromptLogEntry, got: ${entry.payload}")
      case None => fail("Expected Some(ConversationLogEntry)")

  test(
    """"permission-mode" type with data produces PermissionModeLogEntry"""
  ):
    val line =
      """{
        "type":"permission-mode",
        "uuid":"u43",
        "sessionId":"s43",
        "permissionMode":"default"
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
              PermissionModeLogEntry(data),
              _
            )
          ) =>
        assertEquals(data("permissionMode"), "default")
      case Some(entry) =>
        fail(s"Expected PermissionModeLogEntry, got: ${entry.payload}")
      case None => fail("Expected Some(ConversationLogEntry)")

  test(
    """"attachment" type with data produces AttachmentLogEntry"""
  ):
    val line =
      """{
        "type":"attachment",
        "uuid":"u44",
        "sessionId":"s44",
        "fileName":"foo.txt"
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
              AttachmentLogEntry(data),
              _
            )
          ) =>
        assertEquals(data("fileName"), "foo.txt")
      case Some(entry) =>
        fail(s"Expected AttachmentLogEntry, got: ${entry.payload}")
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
              RawLogEntry(entryType, json),
              _
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
              AssistantLogEntry(_, _, Some(usage), _),
              _
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
              AssistantLogEntry(_, _, Some(usage), _),
              _
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

  test("\"queue-operation\" type without operation returns None"):
    val line =
      """{"type":"queue-operation","uuid":"u21","sessionId":"s21","content":"msg"}"""
    val result = ConversationLogParser.parseLogLine(line)
    assertEquals(result, None)

  test("\"user\" type without message field returns None"):
    val line =
      """{"type":"user","uuid":"u22","sessionId":"s22"}"""
    val result = ConversationLogParser.parseLogLine(line)
    assertEquals(result, None)

  test("\"assistant\" type without message field returns None"):
    val line =
      """{"type":"assistant","uuid":"u23","sessionId":"s23"}"""
    val result = ConversationLogParser.parseLogLine(line)
    assertEquals(result, None)

  test("\"permission-mode\" type without uuid parses successfully with uuid = None"):
    val line =
      """{"type":"permission-mode","sessionId":"s43","permissionMode":"default"}"""
    val result = ConversationLogParser.parseLogLine(line)
    result match
      case Some(entry) => assertEquals(entry.uuid, None)
      case None        => fail("Expected Some(ConversationLogEntry)")

  test("\"attachment\" type without uuid parses successfully with uuid = None"):
    val line =
      """{"type":"attachment","sessionId":"s44","fileName":"foo.txt"}"""
    val result = ConversationLogParser.parseLogLine(line)
    result match
      case Some(entry) => assertEquals(entry.uuid, None)
      case None        => fail("Expected Some(ConversationLogEntry)")

  test("parseLogEntry with missing required type field returns None"):
    val json = parser
      .parse("""{"uuid":"u24","sessionId":"s24","message":{"content":"hi"}}""")
      .getOrElse(fail("parse failed"))
    assertEquals(ConversationLogParser.parseLogEntry(json), None)



  test("JSONL line with agentId field extracts Some(agentId)"):
    val line =
      """{
        "type":"user",
        "uuid":"u30",
        "sessionId":"s30",
        "agentId":"agent-abc-123",
        "message":{"content":"hello"}
      }"""
    val result = ConversationLogParser.parseLogLine(line)
    result match
      case Some(entry) => assertEquals(entry.agentId, Some("agent-abc-123"))
      case None        => fail("Expected Some(ConversationLogEntry)")

  test("JSONL line without agentId field extracts None"):
    val line =
      """{"type":"user","uuid":"u31","sessionId":"s31","message":{"content":"hi"}}"""
    val result = ConversationLogParser.parseLogLine(line)
    result match
      case Some(entry) => assertEquals(entry.agentId, None)
      case None        => fail("Expected Some(ConversationLogEntry)")

  test("agentId is excluded from data maps in system entries"):
    val line =
      """{
        "type":"system",
        "uuid":"u32",
        "sessionId":"s32",
        "subtype":"init",
        "agentId":"agent-xyz",
        "apiKeySource":"environment"
      }"""
    val result = ConversationLogParser.parseLogLine(line)
    result match
      case Some(ConversationLogEntry(_, _, _, _, _, _, _, SystemLogEntry(_, data), _)) =>
        assert(!data.contains("agentId"), "agentId must not appear in data map")
        assertEquals(data("apiKeySource"), "environment")
      case Some(entry) => fail(s"Expected SystemLogEntry, got: ${entry.payload}")
      case None        => fail("Expected Some(ConversationLogEntry)")

  test("agentId is excluded from data maps in progress entries"):
    val line =
      """{
        "type":"progress",
        "uuid":"u33",
        "sessionId":"s33",
        "agentId":"agent-xyz",
        "progress":0.5
      }"""
    val result = ConversationLogParser.parseLogLine(line)
    result match
      case Some(ConversationLogEntry(_, _, _, _, _, _, _, ProgressLogEntry(data, _), _)) =>
        assert(!data.contains("agentId"), "agentId must not appear in data map")
        assertEquals(data("progress"), 0.5)
      case Some(entry) => fail(s"Expected ProgressLogEntry, got: ${entry.payload}")
      case None        => fail("Expected Some(ConversationLogEntry)")

  test("malformed timestamp string results in None timestamp"):
    val line =
      """{
        "type":"user",
        "uuid":"u25",
        "sessionId":"s25",
        "timestamp":"not-a-date",
        "message":{"content":"hi"}
      }"""
    val result = ConversationLogParser.parseLogLine(line)
    result match
      case Some(entry) => assertEquals(entry.timestamp, None)
      case None        => fail("Expected Some(ConversationLogEntry)")

  // --- uuid optional tests ---

  test("entry without uuid field is parsed with uuid = None"):
    val line =
      """{"type":"permission-mode","sessionId":"s50","permissionMode":"default"}"""
    val result = ConversationLogParser.parseLogLine(line)
    result match
      case Some(entry) => assertEquals(entry.uuid, None)
      case None        => fail("Expected Some(ConversationLogEntry) for entry without uuid")

  test("entry with uuid field is parsed with uuid = Some(value)"):
    val line =
      """{"type":"user","uuid":"u51","sessionId":"s51","message":{"content":"hi"}}"""
    val result = ConversationLogParser.parseLogLine(line)
    result match
      case Some(entry) => assertEquals(entry.uuid, Some("u51"))
      case None        => fail("Expected Some(ConversationLogEntry)")

  test("file-history-snapshot without uuid is parsed successfully"):
    val line =
      """{"type":"file-history-snapshot","sessionId":"s52","files":["/a.txt"]}"""
    val result = ConversationLogParser.parseLogLine(line)
    result match
      case Some(ConversationLogEntry(_, _, _, _, _, _, _, FileHistorySnapshotLogEntry(data), _)) =>
        assertEquals(data("files"), List("/a.txt"))
      case Some(entry) => fail(s"Expected FileHistorySnapshotLogEntry, got: ${entry.payload}")
      case None        => fail("Expected Some(ConversationLogEntry) for entry without uuid")

  test("transcript with mix of uuid-present and uuid-absent entries parses all entries"):
    val lines = List(
      """{"type":"user","uuid":"u60","sessionId":"s60","message":{"content":"hello"}}""",
      """{"type":"permission-mode","sessionId":"s60","permissionMode":"default"}""",
      """{"type":"assistant","uuid":"u61","sessionId":"s60","message":{"content":[{"type":"text","text":"hi"}]}}"""
    )
    val results = lines.flatMap(ConversationLogParser.parseLogLine)
    assertEquals(results.size, 3, "All three entries should parse, including the one without uuid")

