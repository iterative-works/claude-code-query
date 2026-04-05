// PURPOSE: Integration tests for Session using mock CLI scripts that simulate stream-json protocol
// PURPOSE: Verifies full send/receive lifecycle, stdin JSON format, and session ID extraction
package works.iterative.claude.direct

import ox.*
import works.iterative.claude.core.model.*
import works.iterative.claude.direct.Logger
import works.iterative.claude.direct.internal.testing.SessionMockCliScript
import java.nio.file.{Files, Path}

class SessionIntegrationTest extends munit.FunSuite:

  class MockLogger extends Logger:
    var debugMessages: List[String] = List.empty
    var infoMessages: List[String] = List.empty
    var warnMessages: List[String] = List.empty
    var errorMessages: List[String] = List.empty

    def debug(msg: => String): Unit = debugMessages = msg :: debugMessages
    def info(msg: => String): Unit = infoMessages = msg :: infoMessages
    def warn(msg: => String): Unit = warnMessages = msg :: warnMessages
    def error(msg: => String): Unit = errorMessages = msg :: errorMessages
    def error(msg: => String, exception: Throwable): Unit = errorMessages =
      s"$msg: ${exception.getMessage}" :: errorMessages

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
        val messages = session.send("What is the answer?").runToList()

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
        val _ = session.send("Hello from test").runToList()
      finally session.close()

      // Give a moment for the file to be flushed
      Thread.sleep(50)

      val capturedLines = Files.readAllLines(captureFile)
      assert(
        capturedLines.size >= 1,
        "Expected at least one stdin line captured"
      )

      val firstLine = capturedLines.get(0)
      import io.circe.parser
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
        val messages = session.send("test").runToList()

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
        val messages = session.send("factory test").runToList()

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
