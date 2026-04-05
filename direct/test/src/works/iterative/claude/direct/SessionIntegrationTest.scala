// PURPOSE: Integration tests for Session using mock CLI scripts that simulate stream-json protocol
// PURPOSE: Verifies full send/receive lifecycle, stdin JSON format, and session ID extraction
package works.iterative.claude.direct

import ox.*
import works.iterative.claude.core.model.*
import works.iterative.claude.direct.internal.testing.{
  MockLogger,
  SessionMockCliScript
}
import io.circe.parser
import java.nio.file.{Files, Path}

class SessionIntegrationTest extends munit.FunSuite:

  private val createdScripts = scala.collection.mutable.ListBuffer[Path]()
  private val createdFiles = scala.collection.mutable.ListBuffer[Path]()

  override def afterEach(context: AfterEach): Unit =
    createdScripts.foreach(SessionMockCliScript.cleanup)
    createdFiles.foreach(p => if Files.exists(p) then Files.delete(p))
    createdScripts.clear()
    createdFiles.clear()
    super.afterEach(context)

  // ============================================================
  // IT1: Full single-turn session lifecycle
  // ============================================================

  test("full single-turn session lifecycle with mock CLI") {
    given logger: MockLogger = MockLogger()
    supervised {
      val sessionId = "integ-session-001"
      val script = SessionMockCliScript.createSessionScript(
        initMessages = List(
          SessionMockCliScript.CommonResponses.initMessage(sessionId)
        ),
        turnResponses = List(
          SessionMockCliScript.TurnResponse(
            List(
              SessionMockCliScript.CommonResponses.assistantMessage(
                "The answer is 42"
              ),
              SessionMockCliScript.CommonResponses.resultMessage(sessionId)
            )
          )
        )
      )
      createdScripts += script

      val options = SessionOptions().withClaudeExecutable(script.toString)
      val session = ClaudeCode.session(options)
      try
        session.send("What is the answer?")
        val messages = session.stream().runToList()

        // Should have assistant + result
        assertEquals(messages.length, 2)

        messages.head match
          case AssistantMessage(content) =>
            val texts = content.collect { case TextBlock(t) => t }
            assert(texts.exists(_.contains("The answer is 42")))
          case other => fail(s"Expected AssistantMessage, got: $other")

        messages.last match
          case ResultMessage(
                subtype,
                _,
                _,
                isError,
                _,
                resultSessionId,
                _,
                _,
                _
              ) =>
            assertEquals(subtype, "conversation_result")
            assertEquals(isError, false)
            assertEquals(resultSessionId, sessionId)
          case other => fail(s"Expected ResultMessage, got: $other")
      finally session.close()
    }
  }

  // ============================================================
  // IT2: Mock CLI receives correct stdin JSON
  // ============================================================

  test("mock CLI receives correct SDKUserMessage JSON on stdin") {
    given logger: MockLogger = MockLogger()
    supervised {
      val captureFile = Files.createTempFile("stdin-capture-", ".jsonl")
      createdFiles += captureFile

      val script = SessionMockCliScript.createSessionScript(
        initMessages = Nil,
        turnResponses = List(
          SessionMockCliScript.TurnResponse(
            List(
              SessionMockCliScript.CommonResponses.resultMessage()
            )
          )
        ),
        captureStdinFile = Some(captureFile)
      )
      createdScripts += script

      val options = SessionOptions().withClaudeExecutable(script.toString)
      val session = ClaudeCode.session(options)
      try
        session.send("Hello from test")
        val _ = session.stream().runToList()
      finally session.close()

      val capturedLines = Files.readAllLines(captureFile)
      assert(
        capturedLines.size >= 1,
        "Expected at least one stdin line captured"
      )

      val firstLine = capturedLines.get(0)
      val json = parser
        .parse(firstLine)
        .toOption
        .getOrElse(
          fail(s"stdin line was not valid JSON: $firstLine")
        )
      val cursor = json.hcursor
      assertEquals(
        cursor.downField("type").as[String].toOption,
        Some("user")
      )
      assertEquals(
        cursor
          .downField("message")
          .downField("content")
          .as[String]
          .toOption,
        Some("Hello from test")
      )
    }
  }

  // ============================================================
  // IT3: Session extracts session ID from init message
  // ============================================================

  test("session extracts session ID from init SystemMessage") {
    given logger: MockLogger = MockLogger()
    supervised {
      val expectedSessionId = "extracted-from-init-999"
      val script = SessionMockCliScript.createSessionScript(
        initMessages = List(
          SessionMockCliScript.CommonResponses.initMessage(expectedSessionId)
        ),
        turnResponses = List(
          SessionMockCliScript.TurnResponse(
            List(
              SessionMockCliScript.CommonResponses.resultMessage(
                expectedSessionId
              )
            )
          )
        )
      )
      createdScripts += script

      val options = SessionOptions().withClaudeExecutable(script.toString)
      val session = ClaudeCode.session(options)
      try
        // Session ID should be extracted from init message before any send
        assertEquals(session.sessionId, expectedSessionId)
      finally
        session.close()
    }
  }

  // ============================================================
  // IT4: KeepAlive and StreamEvent messages emitted in Flow
  // ============================================================

  test("KeepAlive and StreamEvent messages are emitted in the Flow") {
    given logger: MockLogger = MockLogger()
    supervised {
      val sessionId = "keepalive-stream-session"
      val script = SessionMockCliScript.createSessionScript(
        initMessages = List(
          SessionMockCliScript.CommonResponses.initMessage(sessionId)
        ),
        turnResponses = List(
          SessionMockCliScript.TurnResponse(
            List(
              SessionMockCliScript.CommonResponses.keepAliveMessage,
              SessionMockCliScript.CommonResponses.streamEventMessage("start"),
              SessionMockCliScript.CommonResponses.assistantMessage(
                "Answer with events"
              ),
              SessionMockCliScript.CommonResponses.resultMessage(sessionId)
            )
          )
        )
      )
      createdScripts += script

      val options = SessionOptions().withClaudeExecutable(script.toString)
      val session = ClaudeCode.session(options)
      try
        session.send("test")
        val messages = session.stream().runToList()

        assert(
          messages.exists(_ == KeepAliveMessage),
          s"Expected KeepAliveMessage in flow: $messages"
        )
        assert(
          messages.exists(_.isInstanceOf[StreamEventMessage]),
          s"Expected StreamEventMessage in flow: $messages"
        )
        assert(
          messages.exists(_.isInstanceOf[AssistantMessage]),
          s"Expected AssistantMessage in flow: $messages"
        )
        assert(
          messages.exists(_.isInstanceOf[ResultMessage]),
          s"Expected ResultMessage in flow: $messages"
        )
      finally session.close()
    }
  }

  // ============================================================
  // IT5: ClaudeCode.session factory creates a working session
  // ============================================================

  test(
    "ClaudeCode.session factory creates a working session via instance API"
  ) {
    given logger: MockLogger = MockLogger()
    supervised {
      val sessionId = "factory-session-002"
      val script = SessionMockCliScript.createSessionScript(
        initMessages = List(
          SessionMockCliScript.CommonResponses.initMessage(sessionId)
        ),
        turnResponses = List(
          SessionMockCliScript.TurnResponse(
            List(
              SessionMockCliScript.CommonResponses.assistantMessage(
                "Factory response"
              ),
              SessionMockCliScript.CommonResponses.resultMessage(sessionId)
            )
          )
        )
      )
      createdScripts += script

      val options = SessionOptions().withClaudeExecutable(script.toString)

      // Use the instance API (ClaudeCode class)
      val claude = ClaudeCode.concurrent
      val session = claude.session(options)
      try
        session.send("factory test")
        val messages = session.stream().runToList()

        assert(
          messages.exists(_.isInstanceOf[AssistantMessage]),
          "Expected AssistantMessage from factory session"
        )
        assert(
          messages.exists(_.isInstanceOf[ResultMessage]),
          "Expected ResultMessage from factory session"
        )
      finally session.close()
    }
  }

  // ============================================================
  // IT6: Full two-turn session lifecycle
  // ============================================================

  test("full two-turn session lifecycle with mock CLI") {
    given logger: MockLogger = MockLogger()
    supervised {
      val initSessionId = "two-turn-init-001"
      val turn1SessionId = "two-turn-result-001"
      val turn2SessionId = "two-turn-result-002"

      val script = SessionMockCliScript.createSessionScript(
        initMessages = List(
          SessionMockCliScript.CommonResponses.initMessage(initSessionId)
        ),
        turnResponses = List(
          SessionMockCliScript.TurnResponse(
            List(
              SessionMockCliScript.CommonResponses.assistantMessage(
                "First turn answer"
              ),
              SessionMockCliScript.CommonResponses.resultMessage(turn1SessionId)
            )
          ),
          SessionMockCliScript.TurnResponse(
            List(
              SessionMockCliScript.CommonResponses.assistantMessage(
                "Second turn answer"
              ),
              SessionMockCliScript.CommonResponses.resultMessage(turn2SessionId)
            )
          )
        )
      )
      createdScripts += script

      val options = SessionOptions().withClaudeExecutable(script.toString)
      val session = ClaudeCode.session(options)
      try
        // Init message is consumed before any send
        assertEquals(session.sessionId, initSessionId)

        session.send("First question")
        val turn1Messages = session.stream().runToList()
        assertEquals(turn1Messages.length, 2)
        turn1Messages.head match
          case AssistantMessage(content) =>
            val texts = content.collect { case TextBlock(t) => t }
            assert(texts.exists(_.contains("First turn answer")))
          case other => fail(s"Expected AssistantMessage, got: $other")
        turn1Messages.last match
          case r: ResultMessage => assertEquals(r.sessionId, turn1SessionId)
          case other            => fail(s"Expected ResultMessage, got: $other")

        assertEquals(session.sessionId, turn1SessionId)

        session.send("Second question")
        val turn2Messages = session.stream().runToList()
        assertEquals(turn2Messages.length, 2)
        turn2Messages.head match
          case AssistantMessage(content) =>
            val texts = content.collect { case TextBlock(t) => t }
            assert(texts.exists(_.contains("Second turn answer")))
          case other => fail(s"Expected AssistantMessage, got: $other")
        turn2Messages.last match
          case r: ResultMessage => assertEquals(r.sessionId, turn2SessionId)
          case other            => fail(s"Expected ResultMessage, got: $other")

        assertEquals(session.sessionId, turn2SessionId)
      finally session.close()
    }
  }

  // ============================================================
  // IT7: Stdin capture shows correct session ID progression
  // ============================================================

  test("stdin capture shows correct session ID progression across turns") {
    given logger: MockLogger = MockLogger()
    supervised {
      val initSessionId = "progression-init-001"
      val turn1SessionId = "progression-turn-001"

      val captureFile = Files.createTempFile("stdin-progression-", ".jsonl")
      createdFiles += captureFile

      val script = SessionMockCliScript.createSessionScript(
        initMessages = List(
          SessionMockCliScript.CommonResponses.initMessage(initSessionId)
        ),
        turnResponses = List(
          SessionMockCliScript.TurnResponse(
            List(
              SessionMockCliScript.CommonResponses.resultMessage(turn1SessionId)
            )
          ),
          SessionMockCliScript.TurnResponse(
            List(
              SessionMockCliScript.CommonResponses.resultMessage(
                "progression-turn-002"
              )
            )
          )
        ),
        captureStdinFile = Some(captureFile)
      )
      createdScripts += script

      val options = SessionOptions().withClaudeExecutable(script.toString)
      val session = ClaudeCode.session(options)
      try
        assertEquals(session.sessionId, initSessionId)
        session.send("Prompt one")
        val _ = session.stream().runToList()
        session.send("Prompt two")
        val _ = session.stream().runToList()
      finally session.close()

      val lines = Files.readAllLines(captureFile)
      assert(
        lines.size >= 2,
        s"Expected at least 2 stdin lines, got ${lines.size}"
      )

      val firstJson = parser
        .parse(lines.get(0))
        .toOption
        .getOrElse(fail(s"First stdin line not valid JSON: ${lines.get(0)}"))
      assertEquals(
        firstJson.hcursor.downField("session_id").as[String].toOption,
        Some(initSessionId),
        "First send should carry init session ID"
      )

      val secondJson = parser
        .parse(lines.get(1))
        .toOption
        .getOrElse(fail(s"Second stdin line not valid JSON: ${lines.get(1)}"))
      assertEquals(
        secondJson.hcursor.downField("session_id").as[String].toOption,
        Some(turn1SessionId),
        "Second send should carry session ID from first turn's ResultMessage"
      )
    }
  }

  // ============================================================
  // IT8: Turn responses with different message counts
  // ============================================================

  test("turns with different message counts emit exactly the right messages") {
    given logger: MockLogger = MockLogger()
    supervised {
      val sessionId = "msg-count-session"
      val script = SessionMockCliScript.createSessionScript(
        initMessages = List(
          SessionMockCliScript.CommonResponses.initMessage(sessionId)
        ),
        turnResponses = List(
          // Turn 1: just assistant + result (2 messages)
          SessionMockCliScript.TurnResponse(
            List(
              SessionMockCliScript.CommonResponses.assistantMessage("Short"),
              SessionMockCliScript.CommonResponses.resultMessage(sessionId)
            )
          ),
          // Turn 2: keepalive + stream_event + assistant + result (4 messages)
          SessionMockCliScript.TurnResponse(
            List(
              SessionMockCliScript.CommonResponses.keepAliveMessage,
              SessionMockCliScript.CommonResponses.streamEventMessage("start"),
              SessionMockCliScript.CommonResponses.assistantMessage("Verbose"),
              SessionMockCliScript.CommonResponses.resultMessage(sessionId)
            )
          )
        )
      )
      createdScripts += script

      val options = SessionOptions().withClaudeExecutable(script.toString)
      val session = ClaudeCode.session(options)
      try
        session.send("Short turn")
        val turn1Messages = session.stream().runToList()
        assertEquals(
          turn1Messages.length,
          2,
          s"Turn 1 should have 2 messages, got: $turn1Messages"
        )
        assert(
          turn1Messages.exists(_.isInstanceOf[AssistantMessage]),
          "Turn 1 must contain AssistantMessage"
        )
        assert(
          turn1Messages.exists(_.isInstanceOf[ResultMessage]),
          "Turn 1 must contain ResultMessage"
        )
        assert(
          !turn1Messages.exists(_ == KeepAliveMessage),
          "Turn 1 must not contain KeepAliveMessage"
        )

        session.send("Verbose turn")
        val turn2Messages = session.stream().runToList()
        assertEquals(
          turn2Messages.length,
          4,
          s"Turn 2 should have 4 messages, got: $turn2Messages"
        )
        // Verify ordering: keepalive, stream_event, assistant, result
        assert(
          turn2Messages(0) == KeepAliveMessage,
          s"Turn 2 message 0 should be KeepAliveMessage, got: ${turn2Messages(0)}"
        )
        assert(
          turn2Messages(1).isInstanceOf[StreamEventMessage],
          s"Turn 2 message 1 should be StreamEventMessage, got: ${turn2Messages(1)}"
        )
        assert(
          turn2Messages(2).isInstanceOf[AssistantMessage],
          s"Turn 2 message 2 should be AssistantMessage, got: ${turn2Messages(2)}"
        )
        assert(
          turn2Messages(3).isInstanceOf[ResultMessage],
          s"Turn 2 message 3 should be ResultMessage, got: ${turn2Messages(3)}"
        )
      finally session.close()
    }
  }
