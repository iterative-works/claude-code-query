// PURPOSE: Compilation tests verifying works.iterative.claude.effectful.* re-exports
// PURPOSE: all types including existing core types, log types, and effectful implementations

package works.iterative.claude.effectful

import munit.CatsEffectSuite
import cats.effect.IO

class EffectfulPackageReexportTest extends CatsEffectSuite:

  test("effectful.* re-exports QueryOptions"):
    val opts = QueryOptions.simple("hello")
    assertEquals(opts.prompt, "hello")

  test("effectful.* re-exports SessionOptions"):
    val opts = SessionOptions.defaults
    assertEquals(opts.cwd, None)

  test("effectful.* re-exports UserMessage"):
    val msg: Message = UserMessage("hello")
    assert(msg.isInstanceOf[UserMessage])

  test("effectful.* re-exports AssistantMessage"):
    val msg: Message = AssistantMessage(List.empty)
    assert(msg.isInstanceOf[AssistantMessage])

  test("effectful.* re-exports SystemMessage"):
    val msg: Message = SystemMessage("init", Map.empty)
    assert(msg.isInstanceOf[SystemMessage])

  test("effectful.* re-exports ContentBlock types"):
    val blocks: List[ContentBlock] = List(
      TextBlock("text"),
      ToolUseBlock("id", "tool", Map.empty),
      ToolResultBlock("id", None, None),
      ThinkingBlock("thoughts", "sig"),
      RedactedThinkingBlock("redacted")
    )
    assertEquals(blocks.size, 5)

  test("effectful.* re-exports PermissionMode"):
    val mode = PermissionMode.AcceptEdits
    assert(mode.isInstanceOf[PermissionMode])

  test("effectful.* re-exports ConversationLogEntry"):
    val _: Class[ConversationLogEntry] = classOf[ConversationLogEntry]

  test("effectful.* re-exports LogEntryPayload sealed trait"):
    val _: Class[LogEntryPayload] = classOf[LogEntryPayload]

  test("effectful.* re-exports UserLogEntry"):
    val entry = UserLogEntry(List(TextBlock("hello")))
    assertEquals(entry.content.size, 1)

  test("effectful.* re-exports AssistantLogEntry"):
    val entry = AssistantLogEntry(List.empty, Some("model"), None, None)
    assertEquals(entry.model, Some("model"))

  test("effectful.* re-exports SystemLogEntry"):
    val entry = SystemLogEntry("init", Map.empty)
    assertEquals(entry.subtype, "init")

  test("effectful.* re-exports ProgressLogEntry"):
    val entry = ProgressLogEntry(Map.empty, None)
    assertEquals(entry.parentToolUseId, None)

  test("effectful.* re-exports QueueOperationLogEntry"):
    val entry = QueueOperationLogEntry("enqueue", None)
    assertEquals(entry.operation, "enqueue")

  test("effectful.* re-exports FileHistorySnapshotLogEntry"):
    val entry = FileHistorySnapshotLogEntry(Map.empty)
    assertEquals(entry.data.size, 0)

  test("effectful.* re-exports LastPromptLogEntry"):
    val entry = LastPromptLogEntry(Map.empty)
    assertEquals(entry.data.size, 0)

  test("effectful.* re-exports RawLogEntry"):
    val json = io.circe.Json.fromString("raw")
    val entry = RawLogEntry("unknown", json)
    assertEquals(entry.entryType, "unknown")

  test("effectful.* re-exports TokenUsage"):
    val usage = TokenUsage(100, 50, None, None, None)
    assertEquals(usage.inputTokens, 100)

  test("effectful.* re-exports LogFileMetadata"):
    val _: Class[LogFileMetadata] = classOf[LogFileMetadata]

  test("effectful.* re-exports ConversationLogIndex trait"):
    val _: Class[ConversationLogIndex[?]] = classOf[ConversationLogIndex[?]]

  test("effectful.* re-exports ConversationLogReader trait"):
    val _: Class[ConversationLogReader[?]] = classOf[ConversationLogReader[?]]

  test("effectful.* re-exports EffectfulConversationLogIndex"):
    val index: ConversationLogIndex[IO] = EffectfulConversationLogIndex()
    val tmpDir = os.temp.dir()
    for sessions <- index.listSessions(tmpDir)
    yield
      assertEquals(sessions.size, 0)
      os.remove.all(tmpDir)

  test("effectful.* re-exports EffectfulConversationLogReader"):
    val reader: ConversationLogReader[IO] = EffectfulConversationLogReader()
    val tmpDir = os.temp.dir()
    val emptyFile = tmpDir / "empty.jsonl"
    os.write(emptyFile, "")
    for entries <- reader.readAll(emptyFile)
    yield
      assertEquals(entries.size, 0)
      os.remove.all(tmpDir)

  test("effectful.* re-exports ProjectPathDecoder"):
    val decoded = ProjectPathDecoder.decode("-home-user-project")
    assertEquals(decoded, "/home/user/project")
