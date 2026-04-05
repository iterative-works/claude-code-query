// PURPOSE: Integration tests for direct Session error handling with mock CLI scripts
// PURPOSE: Verifies process crash, between-turn crash, and malformed JSON resilience
package works.iterative.claude.direct

import ox.*
import works.iterative.claude.core.SessionProcessDied
import works.iterative.claude.core.model.*
import works.iterative.claude.direct.internal.testing.{
  MockLogger,
  SessionMockCliScript
}
import java.nio.file.Path

class SessionErrorIntegrationTest extends munit.FunSuite:

  private val createdScripts = scala.collection.mutable.ListBuffer[Path]()

  override def afterEach(context: AfterEach): Unit =
    createdScripts.foreach { path =>
      val _ = SessionMockCliScript.cleanup(path)
    }
    createdScripts.clear()
    super.afterEach(context)

  // ============================================================
  // I1: Process crash mid-turn surfaces SessionProcessDied with exit code
  // ============================================================

  test(
    "process crash mid-turn: stream() raises SessionProcessDied with exit code"
  ) {
    given logger: MockLogger = MockLogger()
    supervised {
      val exitCode = 42
      val script = SessionMockCliScript.createCrashMidTurnScript(exitCode)
      createdScripts += script

      val options = SessionOptions().withClaudeExecutable(script.toString)
      val session = ClaudeCode.session(options)

      session.send("trigger partial response then crash")

      val caught = intercept[SessionProcessDied] {
        session.stream().runToList()
      }

      assertEquals(
        caught.exitCode,
        Some(exitCode),
        s"Expected exit code $exitCode, got: ${caught.exitCode}"
      )

      session.close()
    }
  }

  // ============================================================
  // I2: Process crash between turns raises SessionProcessDied on next send
  // ============================================================

  test("process crash between turns: second send raises SessionProcessDied") {
    given logger: MockLogger = MockLogger()
    supervised {
      val script =
        SessionMockCliScript.createCrashBetweenTurnsScript(exitCode = 1)
      createdScripts += script

      val options = SessionOptions().withClaudeExecutable(script.toString)
      val session = ClaudeCode.session(options)

      // First turn should complete normally
      session.send("First prompt")
      val turn1Messages = session.stream().runToList()
      assert(
        turn1Messages.exists(_.isInstanceOf[ResultMessage]),
        s"Expected ResultMessage in turn 1: $turn1Messages"
      )

      // Wait for the process to actually exit (up to 5 seconds)
      val proc = session
        .asInstanceOf[
          works.iterative.claude.direct.internal.cli.SessionProcess
        ]
        .underlyingProcess
      val exited = proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
      assert(exited, "Process should have exited within timeout")

      // Second send or stream should fail because the process exited
      val caught = intercept[SessionProcessDied] {
        session.send("Second prompt - process is dead")
      }
      assertEquals(
        caught.exitCode,
        Some(1),
        s"Expected exit code 1, got: ${caught.exitCode}"
      )

      session.close()
    }
  }

  // ============================================================
  // I3: Malformed JSON recovery: valid messages arrive despite bad lines
  // ============================================================

  test(
    "malformed JSON recovery: bad JSON line followed by valid messages; valid messages arrive"
  ) {
    given logger: MockLogger = MockLogger()
    supervised {
      val script = SessionMockCliScript.createMalformedJsonMidTurnScript()
      createdScripts += script

      val options = SessionOptions().withClaudeExecutable(script.toString)
      val session = ClaudeCode.session(options)

      session.send("test")
      val messages = session.stream().runToList()

      assert(
        messages.exists(_.isInstanceOf[AssistantMessage]),
        s"Expected AssistantMessage: $messages"
      )
      assert(
        messages.exists(_.isInstanceOf[ResultMessage]),
        s"Expected ResultMessage: $messages"
      )

      session.close()
    }
  }

  // ============================================================
  // I4: Multiple malformed lines do not accumulate errors
  // ============================================================

  test(
    "multiple malformed JSON lines do not accumulate errors; all valid messages arrive"
  ) {
    given logger: MockLogger = MockLogger()
    supervised {
      val sessionId = SessionMockCliScript.CommonResponses.initSessionId
      // Turn with several bad JSON lines interspersed with valid messages
      val script = SessionMockCliScript.createSessionScript(
        initMessages = List(SessionMockCliScript.CommonResponses.initMessage()),
        turnResponses = List(
          SessionMockCliScript.TurnResponse(
            List(
              "{bad json 1}",
              SessionMockCliScript.CommonResponses.assistantMessage(
                "First valid message"
              ),
              "{bad json 2}",
              "{bad json 3}",
              SessionMockCliScript.CommonResponses.assistantMessage(
                "Second valid message"
              ),
              SessionMockCliScript.CommonResponses.resultMessage(sessionId)
            )
          )
        )
      )
      createdScripts += script

      val options = SessionOptions().withClaudeExecutable(script.toString)
      val session = ClaudeCode.session(options)

      session.send("test")
      val messages = session.stream().runToList()

      val assistantMessages = messages.collect { case a: AssistantMessage => a }
      assertEquals(
        assistantMessages.length,
        2,
        s"Expected 2 AssistantMessages, got: $messages"
      )

      val resultMessages = messages.collect { case r: ResultMessage => r }
      assertEquals(
        resultMessages.length,
        1,
        s"Expected 1 ResultMessage, got: $messages"
      )

      session.close()
    }
  }
