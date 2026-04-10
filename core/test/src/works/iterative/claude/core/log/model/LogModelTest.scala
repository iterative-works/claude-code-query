package works.iterative.claude.core.log.model

// PURPOSE: Unit tests for conversation log domain types
// PURPOSE: Verifies correct construction and field access for all log model types

import munit.FunSuite
import java.time.Instant
import works.iterative.claude.core.model.{ContentBlock, TextBlock}
import io.circe.Json

class LogModelTest extends FunSuite:

  // TokenUsage tests

  test("TokenUsage should hold required token counts"):
    val usage = TokenUsage(
      inputTokens = 100,
      outputTokens = 50,
      cacheCreationInputTokens = None,
      cacheReadInputTokens = None,
      serviceTier = None
    )
    assertEquals(usage.inputTokens, 100)
    assertEquals(usage.outputTokens, 50)

  test("TokenUsage should hold optional cache token counts"):
    val usage = TokenUsage(
      inputTokens = 200,
      outputTokens = 80,
      cacheCreationInputTokens = Some(30),
      cacheReadInputTokens = Some(15),
      serviceTier = Some("standard")
    )
    assertEquals(usage.cacheCreationInputTokens, Some(30))
    assertEquals(usage.cacheReadInputTokens, Some(15))
    assertEquals(usage.serviceTier, Some("standard"))

  // LogEntryPayload variant tests

  test("UserLogEntry should hold content blocks"):
    val content: List[ContentBlock] = List(TextBlock("hello"))
    val entry = UserLogEntry(content)
    assertEquals(entry.content, content)

  test("AssistantLogEntry should hold content and optional metadata"):
    val usage = TokenUsage(100, 50, None, None, None)
    val entry = AssistantLogEntry(
      content = List(TextBlock("response")),
      model = Some("claude-3-5-sonnet"),
      usage = Some(usage),
      requestId = Some("req-123")
    )
    assertEquals(entry.content, List(TextBlock("response")))
    assertEquals(entry.model, Some("claude-3-5-sonnet"))
    assertEquals(entry.usage, Some(usage))
    assertEquals(entry.requestId, Some("req-123"))

  test("AssistantLogEntry should allow all optional fields to be None"):
    val entry = AssistantLogEntry(
      content = List.empty,
      model = None,
      usage = None,
      requestId = None
    )
    assertEquals(entry.model, None)
    assertEquals(entry.usage, None)

  test("SystemLogEntry should hold subtype and data map"):
    val data = Map[String, Any]("key" -> "value", "count" -> 42)
    val entry = SystemLogEntry("init", data)
    assertEquals(entry.subtype, "init")
    assertEquals(entry.data("key"), "value")

  test("ProgressLogEntry should hold data and optional parentToolUseId"):
    val data = Map[String, Any]("progress" -> 0.5)
    val entry = ProgressLogEntry(data, Some("tool-use-id-1"))
    assertEquals(entry.data, data)
    assertEquals(entry.parentToolUseId, Some("tool-use-id-1"))

  test("QueueOperationLogEntry should hold operation and optional content"):
    val entry = QueueOperationLogEntry("enqueue", Some("message content"))
    assertEquals(entry.operation, "enqueue")
    assertEquals(entry.content, Some("message content"))

  test("FileHistorySnapshotLogEntry should hold data map"):
    val data = Map[String, Any]("files" -> List("/a.txt", "/b.txt"))
    val entry = FileHistorySnapshotLogEntry(data)
    assertEquals(entry.data, data)

  test("LastPromptLogEntry should hold data map"):
    val data = Map[String, Any]("prompt" -> "last prompt text")
    val entry = LastPromptLogEntry(data)
    assertEquals(entry.data, data)

  test("RawLogEntry should hold entry type and raw JSON"):
    val json = Json.obj("foo" -> Json.fromString("bar"))
    val entry = RawLogEntry("unknown_type", json)
    assertEquals(entry.entryType, "unknown_type")
    assertEquals(entry.json, json)

  test("All LogEntryPayload variants are subtypes of LogEntryPayload"):
    val variants: List[LogEntryPayload] = List(
      UserLogEntry(List.empty),
      AssistantLogEntry(List.empty, None, None, None),
      SystemLogEntry("test", Map.empty),
      ProgressLogEntry(Map.empty, None),
      QueueOperationLogEntry("op", None),
      FileHistorySnapshotLogEntry(Map.empty),
      LastPromptLogEntry(Map.empty),
      RawLogEntry("raw", Json.Null)
    )
    assertEquals(variants.size, 8)

  // ConversationLogEntry tests

  test("ConversationLogEntry should hold envelope metadata and payload"):
    val payload = UserLogEntry(List(TextBlock("hello")))
    val now = Instant.now()
    val entry = ConversationLogEntry(
      uuid = Some("uuid-001"),
      parentUuid = Some("parent-uuid-000"),
      timestamp = Some(now),
      sessionId = "session-abc",
      isSidechain = false,
      cwd = Some("/home/user"),
      version = Some("1.0"),
      payload = payload
    )
    assertEquals(entry.uuid, Some("uuid-001"))
    assertEquals(entry.parentUuid, Some("parent-uuid-000"))
    assertEquals(entry.timestamp, Some(now))
    assertEquals(entry.sessionId, "session-abc")
    assertEquals(entry.isSidechain, false)
    assertEquals(entry.cwd, Some("/home/user"))
    assertEquals(entry.version, Some("1.0"))
    assertEquals(entry.payload, payload)

  test("ConversationLogEntry should allow optional fields to be None"):
    val entry = ConversationLogEntry(
      uuid = Some("uuid-002"),
      parentUuid = None,
      timestamp = None,
      sessionId = "session-xyz",
      isSidechain = true,
      cwd = None,
      version = None,
      payload = SystemLogEntry("init", Map.empty)
    )
    assertEquals(entry.parentUuid, None)
    assertEquals(entry.timestamp, None)
    assertEquals(entry.cwd, None)
    assertEquals(entry.version, None)

  test("ConversationLogEntry can hold each LogEntryPayload variant"):
    val sessionId = "session-test"
    val entries = List(
      ConversationLogEntry(
        Some("u1"),
        None,
        None,
        sessionId,
        false,
        None,
        None,
        UserLogEntry(List.empty)
      ),
      ConversationLogEntry(
        Some("u2"),
        None,
        None,
        sessionId,
        false,
        None,
        None,
        AssistantLogEntry(List.empty, None, None, None)
      ),
      ConversationLogEntry(
        Some("u3"),
        None,
        None,
        sessionId,
        false,
        None,
        None,
        SystemLogEntry("init", Map.empty)
      ),
      ConversationLogEntry(
        Some("u4"),
        None,
        None,
        sessionId,
        false,
        None,
        None,
        ProgressLogEntry(Map.empty, None)
      ),
      ConversationLogEntry(
        Some("u5"),
        None,
        None,
        sessionId,
        false,
        None,
        None,
        QueueOperationLogEntry("op", None)
      ),
      ConversationLogEntry(
        Some("u6"),
        None,
        None,
        sessionId,
        false,
        None,
        None,
        FileHistorySnapshotLogEntry(Map.empty)
      ),
      ConversationLogEntry(
        Some("u7"),
        None,
        None,
        sessionId,
        false,
        None,
        None,
        LastPromptLogEntry(Map.empty)
      ),
      ConversationLogEntry(
        Some("u8"),
        None,
        None,
        sessionId,
        false,
        None,
        None,
        RawLogEntry("unknown", Json.Null)
      )
    )
    assertEquals(entries.size, 8)

  // LogFileMetadata tests

  test("LogFileMetadata should hold file metadata"):
    val path = os.Path("/home/user/.claude/projects/session.jsonl")
    val now = Instant.now()
    val meta = LogFileMetadata(
      path = path,
      sessionId = "session-123",
      summary = Some("a conversation about Scala"),
      lastModified = now,
      fileSize = 4096L,
      cwd = Some("/home/user/project"),
      gitBranch = Some("main"),
      createdAt = Some(now)
    )
    assertEquals(meta.path, path)
    assertEquals(meta.sessionId, "session-123")
    assertEquals(meta.summary, Some("a conversation about Scala"))
    assertEquals(meta.lastModified, now)
    assertEquals(meta.fileSize, 4096L)
    assertEquals(meta.cwd, Some("/home/user/project"))
    assertEquals(meta.gitBranch, Some("main"))
    assertEquals(meta.createdAt, Some(now))

  // SubAgentMetadata tests

  test("SubAgentMetadata should hold all fields"):
    val path = os.Path("/tmp/subagent/transcript.jsonl")
    val meta = SubAgentMetadata(
      agentId = "agent-abc-123",
      agentType = Some("coder"),
      description = Some("Writes code"),
      transcriptPath = path
    )
    assertEquals(meta.agentId, "agent-abc-123")
    assertEquals(meta.agentType, Some("coder"))
    assertEquals(meta.description, Some("Writes code"))
    assertEquals(meta.transcriptPath, path)

  test("SubAgentMetadata should allow optional fields to be None"):
    val path = os.Path("/tmp/subagent/transcript.jsonl")
    val meta = SubAgentMetadata(
      agentId = "agent-xyz",
      agentType = None,
      description = None,
      transcriptPath = path
    )
    assertEquals(meta.agentType, None)
    assertEquals(meta.description, None)

  test("LogFileMetadata should allow optional fields to be None"):
    val path = os.Path("/tmp/session.jsonl")
    val now = Instant.now()
    val meta = LogFileMetadata(
      path = path,
      sessionId = "session-456",
      summary = None,
      lastModified = now,
      fileSize = 0L,
      cwd = None,
      gitBranch = None,
      createdAt = None
    )
    assertEquals(meta.summary, None)
    assertEquals(meta.cwd, None)
    assertEquals(meta.gitBranch, None)
    assertEquals(meta.createdAt, None)
