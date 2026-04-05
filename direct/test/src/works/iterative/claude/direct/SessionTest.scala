// PURPOSE: Unit tests for Session trait and SessionProcess implementation
// PURPOSE: Verifies session lifecycle, message flow, and session ID extraction
package works.iterative.claude.direct

import ox.*
import works.iterative.claude.core.model.*
import works.iterative.claude.direct.internal.testing.{
  MockCliScript,
  MockLogger,
  SessionMockCliScript
}
import io.circe.syntax.*
import io.circe.parser
import java.nio.file.Path

class SessionTest extends munit.FunSuite:

  // Track created mock scripts for cleanup
  private val createdScripts = scala.collection.mutable.ListBuffer[Path]()

  override def afterEach(context: AfterEach): Unit =
    createdScripts.foreach { path =>
      val _ = MockCliScript.cleanup(path)
      val _ = SessionMockCliScript.cleanup(path)
    }
    createdScripts.clear()
    super.afterEach(context)

  // ============================================================
  // T2: SDKUserMessage stdin encoding test
  // ============================================================

  test("SDKUserMessage is correctly encoded for stdin") {
    val msg = SDKUserMessage(content = "Hello, Claude!", sessionId = "sess-42")
    val json = msg.asJson.noSpaces

    val parsed = parser.parse(json).toOption.get
    val cursor = parsed.hcursor

    assertEquals(cursor.downField("type").as[String].toOption, Some("user"))
    assertEquals(
      cursor
        .downField("message")
        .downField("role")
        .as[String]
        .toOption,
      Some("user")
    )
    assertEquals(
      cursor
        .downField("message")
        .downField("content")
        .as[String]
        .toOption,
      Some("Hello, Claude!")
    )
    assertEquals(
      cursor.downField("session_id").as[String].toOption,
      Some("sess-42")
    )
  }

  test("SDKUserMessage with pending session ID uses 'pending' literal") {
    val msg = SDKUserMessage(content = "First prompt", sessionId = "pending")
    val json = msg.asJson.noSpaces
    val parsed = parser.parse(json).toOption.get
    assertEquals(
      parsed.hcursor.downField("session_id").as[String].toOption,
      Some("pending")
    )
  }

  // ============================================================
  // T3: Session ID extraction from SystemMessage init
  // ============================================================

  test("Session ID is extracted from SystemMessage with subtype init") {
    val initMsg = SystemMessage(
      subtype = "init",
      data = Map("session_id" -> "extracted-session-id-123")
    )
    val extracted = initMsg.data.get("session_id").map(_.toString)
    assertEquals(extracted, Some("extracted-session-id-123"))
  }

  test("Non-init SystemMessage does not provide session ID") {
    val userContextMsg = SystemMessage(
      subtype = "user_context",
      data = Map("context_user_id" -> "user_123")
    )
    // We only extract from "init" subtype
    val extracted =
      if userContextMsg.subtype == "init" then
        userContextMsg.data.get("session_id").map(_.toString)
      else None
    assertEquals(extracted, None)
  }

  // ============================================================
  // T4: Session ID defaults to "pending"
  // ============================================================

  test("Session ID defaults to 'pending' when no init message received") {
    // This is a behavioral test: if we create a session against a script
    // that doesn't emit an init message before the first send, we get "pending"
    given logger: MockLogger = MockLogger()
    supervised {
      val noInitScript = MockCliScript.createSimpleScript(
        List(
          // No init message - just jump straight to result
          s"""{"type":"result","subtype":"conversation_result","duration_ms":100,"duration_api_ms":50,"is_error":false,"num_turns":1,"session_id":"real-session-id"}"""
        ),
        delayMs = 0
      )
      createdScripts += noInitScript

      val options = SessionOptions().withClaudeExecutable(noInitScript.toString)
      val session = ClaudeCode.session(options)

      // Before any send, if no init message was received, sessionId is "pending"
      assertEquals(session.sessionId, "pending")
      session.close()
    }
  }

  // ============================================================
  // T5: ResultMessage signals end of Flow
  // ============================================================

  test("Flow completes after emitting ResultMessage") {
    given logger: MockLogger = MockLogger()
    supervised {
      val sessionId = "sess-flow-test"
      // Session script emits assistant + result for the first turn,
      // then ignores subsequent turns (no more responses configured)
      val script = SessionMockCliScript.createSessionScript(
        initMessages = Nil,
        turnResponses = List(
          SessionMockCliScript.TurnResponse(
            List(
              """{"type":"assistant","message":{"content":[{"type":"text","text":"Hello!"}]}}""",
              s"""{"type":"result","subtype":"conversation_result","duration_ms":100,"duration_api_ms":50,"is_error":false,"num_turns":1,"session_id":"$sessionId"}"""
            )
          )
        )
      )
      createdScripts += script

      val options = SessionOptions().withClaudeExecutable(script.toString)
      val session = ClaudeCode.session(options)
      try
        val messages = session.send("test").runToList()

        // Should have assistant + result only
        val resultMessages = messages.collect { case r: ResultMessage => r }
        assertEquals(resultMessages.length, 1)

        val assistantMessages = messages.collect { case a: AssistantMessage =>
          a
        }
        assertEquals(assistantMessages.length, 1)

        // Total should be 2 (assistant + result)
        assertEquals(messages.length, 2)
      finally session.close()
    }
  }

  // ============================================================
  // T6: Session ID updated from ResultMessage
  // ============================================================

  test("Session ID is updated from ResultMessage after send completes") {
    given logger: MockLogger = MockLogger()
    supervised {
      val script = SessionMockCliScript.createSessionScript(
        initMessages = Nil,
        turnResponses = List(
          SessionMockCliScript.TurnResponse(
            List(
              s"""{"type":"result","subtype":"conversation_result","duration_ms":100,"duration_api_ms":50,"is_error":false,"num_turns":1,"session_id":"updated-session-id-456"}"""
            )
          )
        )
      )
      createdScripts += script

      val options = SessionOptions().withClaudeExecutable(script.toString)
      val session = ClaudeCode.session(options)
      try
        // Run the flow to completion
        val _ = session.send("test").runToList()

        // After send completes, session ID should be updated from ResultMessage
        assertEquals(session.sessionId, "updated-session-id-456")
      finally session.close()
    }
  }

  // ============================================================
  // T7: Close terminates process
  // ============================================================

  test("close() terminates the underlying process") {
    given logger: MockLogger = MockLogger()
    supervised {
      // Create a session script that stays alive waiting for stdin (no turnResponses
      // means it just reads until EOF, simulating a long-lived process)
      val hangingScript = SessionMockCliScript.createSessionScript(
        initMessages = Nil,
        turnResponses = Nil
      )
      createdScripts += hangingScript

      val options =
        SessionOptions().withClaudeExecutable(hangingScript.toString)
      val session = ClaudeCode.session(options)

      // The session wraps a Process — extract it to verify it's terminated after close
      // We know SessionProcess holds the process, so we verify indirectly:
      // after close(), sending should fail because stdin is closed
      session.close()

      // Verify the session is no longer usable — send should throw because
      // the underlying stdin writer is closed
      val caught = intercept[Exception] {
        session.send("should fail").runToList()
      }
      assert(
        caught != null,
        "Expected an exception when sending to a closed session"
      )
    }
  }
