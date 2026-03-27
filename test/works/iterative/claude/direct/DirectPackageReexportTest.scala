// PURPOSE: Compilation tests verifying works.iterative.claude.direct.* re-exports
// PURPOSE: all log-related types, thinking block types, service traits and implementations

package works.iterative.claude.direct

import munit.FunSuite

class DirectPackageReexportTest extends FunSuite:

  test("direct.* re-exports ThinkingBlock"):
    val block: ContentBlock = ThinkingBlock("some thoughts", "sig-abc")
    block match
      case ThinkingBlock(thinking, signature) =>
        assertEquals(thinking, "some thoughts")
        assertEquals(signature, "sig-abc")
      case _ => fail("Expected ThinkingBlock")

  test("direct.* re-exports RedactedThinkingBlock"):
    val block: ContentBlock = RedactedThinkingBlock("redacted-data")
    block match
      case RedactedThinkingBlock(data) =>
        assertEquals(data, "redacted-data")
      case _ => fail("Expected RedactedThinkingBlock")

  test("direct.* re-exports ConversationLogEntry"):
    val _: Class[ConversationLogEntry] = classOf[ConversationLogEntry]

  test("direct.* re-exports LogEntryPayload sealed trait"):
    val _: Class[LogEntryPayload] = classOf[LogEntryPayload]

  test("direct.* re-exports UserLogEntry"):
    val entry = UserLogEntry(List(TextBlock("hello")))
    assertEquals(entry.content.size, 1)

  test("direct.* re-exports AssistantLogEntry"):
    val entry = AssistantLogEntry(
      content = List(TextBlock("hello")),
      model = Some("claude-3-5-sonnet"),
      usage = None,
      requestId = None
    )
    assertEquals(entry.model, Some("claude-3-5-sonnet"))

  test("direct.* re-exports SystemLogEntry"):
    val entry = SystemLogEntry("init", Map.empty)
    assertEquals(entry.subtype, "init")

  test("direct.* re-exports ProgressLogEntry"):
    val entry = ProgressLogEntry(Map.empty, None)
    assertEquals(entry.parentToolUseId, None)

  test("direct.* re-exports QueueOperationLogEntry"):
    val entry = QueueOperationLogEntry("enqueue", Some("content"))
    assertEquals(entry.operation, "enqueue")

  test("direct.* re-exports FileHistorySnapshotLogEntry"):
    val entry = FileHistorySnapshotLogEntry(Map("key" -> "value"))
    assertEquals(entry.data.size, 1)

  test("direct.* re-exports LastPromptLogEntry"):
    val entry = LastPromptLogEntry(Map.empty)
    assertEquals(entry.data.size, 0)

  test("direct.* re-exports RawLogEntry"):
    val json = io.circe.Json.fromString("raw")
    val entry = RawLogEntry("unknown", json)
    assertEquals(entry.entryType, "unknown")

  test("direct.* re-exports TokenUsage"):
    val usage = TokenUsage(100, 50, None, None, None)
    assertEquals(usage.inputTokens, 100)
    assertEquals(usage.outputTokens, 50)

  test("direct.* re-exports LogFileMetadata"):
    val _: Class[LogFileMetadata] = classOf[LogFileMetadata]

  test("direct.* re-exports ConversationLogIndex trait"):
    val _: Class[ConversationLogIndex[?]] = classOf[ConversationLogIndex[?]]

  test("direct.* re-exports ConversationLogReader trait"):
    val _: Class[ConversationLogReader[?]] = classOf[ConversationLogReader[?]]

  test("direct.* re-exports DirectConversationLogIndex"):
    val index: ConversationLogIndex[[A] =>> A] = DirectConversationLogIndex()
    val tmpDir = os.temp.dir()
    try
      val sessions: Seq[LogFileMetadata] = index.listSessions(tmpDir)
      assertEquals(sessions.size, 0)
    finally os.remove.all(tmpDir)

  test("direct.* re-exports DirectConversationLogReader"):
    val reader: ConversationLogReader[[A] =>> A] = DirectConversationLogReader()
    val tmpDir = os.temp.dir()
    try
      val emptyFile = tmpDir / "empty.jsonl"
      os.write(emptyFile, "")
      assertEquals(reader.readAll(emptyFile).size, 0)
    finally os.remove.all(tmpDir)

  test("direct.* re-exports ProjectPathDecoder"):
    val decoded = ProjectPathDecoder.decode("-home-user-project")
    assertEquals(decoded, "/home/user/project")
