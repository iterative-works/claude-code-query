// PURPOSE: Tests for session error handling in the direct (Ox) API
// PURPOSE: Covers process crash, closed session, and malformed JSON scenarios
package works.iterative.claude.direct

import ox.*
import works.iterative.claude.core.{SessionClosedError, SessionProcessDied}
import works.iterative.claude.core.model.*
import works.iterative.claude.direct.internal.testing.{
  MockLogger,
  SessionMockCliScript
}
import java.nio.file.Path

class SessionErrorTest extends munit.FunSuite:

  private val createdScripts = scala.collection.mutable.ListBuffer[Path]()

  override def afterEach(context: AfterEach): Unit =
    createdScripts.foreach { path =>
      val _ = SessionMockCliScript.cleanup(path)
    }
    createdScripts.clear()
    super.afterEach(context)

  // ============================================================
  // E1: send after close() throws SessionClosedError
  // ============================================================

  test("send after close() throws SessionClosedError") {
    given logger: MockLogger = MockLogger()
    supervised {
      val script = SessionMockCliScript.createSessionScript(
        initMessages = Nil,
        turnResponses = Nil
      )
      createdScripts += script

      val options = SessionOptions().withClaudeExecutable(script.toString)
      val session = ClaudeCode.session(options)
      session.close()

      val caught = intercept[SessionClosedError] {
        session.send("should fail")
      }
      assert(
        caught.message.contains("closed"),
        s"Expected 'closed' in message: ${caught.message}"
      )
    }
  }

  // ============================================================
  // E2: close() is idempotent
  // ============================================================

  test("close() is idempotent - calling it twice does not throw") {
    given logger: MockLogger = MockLogger()
    supervised {
      val script = SessionMockCliScript.createSessionScript(
        initMessages = Nil,
        turnResponses = Nil
      )
      createdScripts += script

      val options = SessionOptions().withClaudeExecutable(script.toString)
      val session = ClaudeCode.session(options)
      session.close()
      session.close() // should not throw
    }
  }

  // ============================================================
  // E3: send to dead process throws SessionProcessDied
  // ============================================================

  test("send to a dead process throws SessionProcessDied") {
    given logger: MockLogger = MockLogger()
    supervised {
      // Script that exits immediately without reading stdin
      val script = SessionMockCliScript.createImmediateExitScript(exitCode = 1)
      createdScripts += script

      val options = SessionOptions().withClaudeExecutable(script.toString)
      val session = ClaudeCode.session(options)

      // Wait for the process to actually exit (up to 5 seconds)
      val proc = session
        .asInstanceOf[
          works.iterative.claude.direct.internal.cli.SessionProcess
        ]
        .underlyingProcess
      val exited = proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
      assert(exited, "Process should have exited within timeout")

      val _ = intercept[SessionProcessDied] {
        session.send("should fail - process is dead")
      }
      session.close()
    }
  }

  // ============================================================
  // E4: stream raises SessionProcessDied when process exits mid-turn
  // ============================================================

  test(
    "stream raises SessionProcessDied when process exits mid-turn (before ResultMessage)"
  ) {
    given logger: MockLogger = MockLogger()
    supervised {
      val script = SessionMockCliScript.createCrashMidTurnScript(exitCode = 1)
      createdScripts += script

      val options = SessionOptions().withClaudeExecutable(script.toString)
      val session = ClaudeCode.session(options)

      session.send("trigger the crash")

      val _ = intercept[SessionProcessDied] {
        session.stream().runToList()
      }
      session.close()
    }
  }

  // ============================================================
  // E5: Malformed JSON line mid-turn is skipped and stream completes normally
  // ============================================================

  test(
    "malformed JSON line mid-turn is skipped and stream completes with valid messages"
  ) {
    given logger: MockLogger = MockLogger()
    supervised {
      val script = SessionMockCliScript.createMalformedJsonMidTurnScript()
      createdScripts += script

      val options = SessionOptions().withClaudeExecutable(script.toString)
      val session = ClaudeCode.session(options)

      session.send("test")
      val messages = session.stream().runToList()

      // Should have received the valid assistant message + result, not crash on the bad line
      assert(
        messages.exists(_.isInstanceOf[AssistantMessage]),
        s"Expected AssistantMessage in stream: $messages"
      )

      val resultMessages = messages.collect { case r: ResultMessage => r }
      assertEquals(
        resultMessages.length,
        1,
        s"Expected exactly 1 ResultMessage, got: $messages"
      )

      session.close()
    }
  }
