// PURPOSE: Compilation/usage tests verifying works.iterative.claude.zio.* re-exports
// PURPOSE: Covers core model types, the CLIError ADT, log types, and ZIO log implementations

package works.iterative.claude.zio

import zio.*
import zio.test.*
import works.iterative.claude.zio.internal.testing.ClaudeZioSpec

object ZioPackageReexportTest extends ClaudeZioSpec:
  def spec = suite("zio package re-exports")(
    test("re-exports QueryOptions and SessionOptions"):
      assertTrue(
        QueryOptions.simple("hello").prompt == "hello",
        SessionOptions.defaults.cwd.isEmpty
      ),
    test("re-exports the Message hierarchy"):
      val messages: List[Message] = List(
        UserMessage("h"),
        AssistantMessage(Nil),
        SystemMessage("init", Map.empty),
        ResultMessage("s", 1, 1, false, 1, "id")
      )
      assertTrue(messages.size == 4),
    test("re-exports the ContentBlock types"):
      val blocks: List[ContentBlock] = List(
        TextBlock("t"),
        ToolUseBlock("id", "tool", Map.empty),
        ToolResultBlock("id", None, None),
        ThinkingBlock("th", "sig"),
        RedactedThinkingBlock("r")
      )
      assertTrue(blocks.size == 5),
    test("re-exports PermissionMode"):
      assertTrue(PermissionMode.AcceptEdits.isInstanceOf[PermissionMode]),
    test("re-exports the CLIError ADT"):
      val errors: List[CLIError] = List(
        CLINotFoundError("a"),
        NodeJSNotFoundError("b"),
        ProcessExecutionError(1, "e", Nil),
        ProcessTimeoutError(
          scala.concurrent.duration.FiniteDuration(1, "second"),
          Nil
        ),
        ConfigurationError("p", "v", "r"),
        JsonParsingError("line", 1, new RuntimeException("x")),
        EnvironmentValidationError(Nil, "r"),
        SessionProcessDied(None, ""),
        SessionClosedError("s")
      )
      assertTrue(errors.size == 9),
    test("re-exports the log model types"):
      val entries: List[LogEntryPayload] = List(
        UserLogEntry(List(TextBlock("h"))),
        AssistantLogEntry(List.empty, Some("model"), None, None),
        SystemLogEntry("init", Map.empty),
        ProgressLogEntry(Map.empty, None),
        QueueOperationLogEntry("enqueue", None),
        FileHistorySnapshotLogEntry(Map.empty),
        LastPromptLogEntry(Map.empty),
        RawLogEntry("unknown", io.circe.Json.fromString("raw"))
      )
      val usage = TokenUsage(100, 50, None, None, None)
      assertTrue(entries.size == 8, usage.inputTokens == 100),
    test("re-exports the log service traits"):
      val _ : Class[ConversationLogIndex[?]]  = classOf[ConversationLogIndex[?]]
      val _ : Class[ConversationLogReader[?]] = classOf[ConversationLogReader[?]]
      assertCompletes,
    test("re-exports ZioConversationLogIndex"):
      for
        tmpDir                          <- ZIO.attempt(os.temp.dir())
        index: ConversationLogIndex[Task] <- ZioConversationLogIndex()
        sessions                        <- index.listSessions(tmpDir)
        _                               <- ZIO.attempt(os.remove.all(tmpDir)).ignore
      yield assertTrue(sessions.isEmpty),
    test("re-exports ZioConversationLogReader"):
      val reader: ConversationLogReader[Task] = ZioConversationLogReader()
      for
        tmpDir  <- ZIO.attempt(os.temp.dir())
        file     = tmpDir / "empty.jsonl"
        _       <- ZIO.attempt(os.write(file, ""))
        entries <- reader.readAll(file)
        _       <- ZIO.attempt(os.remove.all(tmpDir)).ignore
      yield assertTrue(entries.isEmpty),
    test("re-exports ProjectPathDecoder"):
      assertTrue(
        ProjectPathDecoder.decode("-home-user-project") == "/home/user/project"
      )
  )
