// PURPOSE: Integration tests for effectful Session error handling with mock CLI scripts
// PURPOSE: Verifies process crash, between-turn crash, malformed JSON resilience, and resource cleanup
package works.iterative.claude.effectful

import cats.effect.IO
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.testing.TestingLogger
import works.iterative.claude.core.{SessionProcessDied}
import works.iterative.claude.core.model.*
import works.iterative.claude.direct.internal.testing.SessionMockCliScript

class SessionErrorIntegrationTest extends CatsEffectSuite:

  given Logger[IO] = TestingLogger.impl[IO]()

  // ============================================================
  // I1: Process crash mid-turn surfaces SessionProcessDied
  // ============================================================

  test("process crash mid-turn: stream raises SessionProcessDied") {
    val exitCode = 42
    val script = SessionMockCliScript.createCrashMidTurnScript(exitCode)
    val options = SessionOptions().withClaudeExecutable(script.toString)
    ClaudeCode
      .session(options)
      .use { session =>
        for
          _ <- session.send("trigger crash")
          result <- session.stream.compile.drain.attempt
        yield
          assert(result.isLeft, s"Expected stream to fail: $result")
          val err = result.swap.toOption
          assert(
            err.exists(_.isInstanceOf[SessionProcessDied]),
            s"Expected SessionProcessDied, got: $err"
          )
      }
      .guarantee(IO { SessionMockCliScript.cleanup(script): Unit })
  }

  // ============================================================
  // I2: Process crash between turns raises SessionProcessDied on second send
  // ============================================================

  test("process crash between turns: second send raises SessionProcessDied") {
    val script =
      SessionMockCliScript.createCrashBetweenTurnsScript(exitCode = 1)
    val options = SessionOptions().withClaudeExecutable(script.toString)
    ClaudeCode
      .session(options)
      .use { session =>
        for
          // First turn should complete normally
          _ <- session.send("First prompt")
          turn1Messages <- session.stream.compile.toList
          _ = assert(
            turn1Messages.exists(_.isInstanceOf[ResultMessage]),
            s"Expected ResultMessage in turn 1: $turn1Messages"
          )
          // Wait for process to exit
          _ <- IO.sleep(scala.concurrent.duration.FiniteDuration(200, "ms"))
          // Second send should fail
          result <- session.send("Second prompt").attempt
        yield
          assert(result.isLeft, s"Expected second send to fail: $result")
          assert(
            result.swap.toOption.exists(_.isInstanceOf[SessionProcessDied]),
            s"Expected SessionProcessDied, got: ${result.swap.toOption}"
          )
      }
      .guarantee(IO { SessionMockCliScript.cleanup(script): Unit })
  }

  // ============================================================
  // I3: Malformed JSON recovery with mock script
  // ============================================================

  test(
    "malformed JSON recovery: bad JSON line followed by valid messages; valid messages arrive"
  ) {
    val script = SessionMockCliScript.createMalformedJsonMidTurnScript()
    val options = SessionOptions().withClaudeExecutable(script.toString)
    ClaudeCode
      .session(options)
      .use { session =>
        for
          _ <- session.send("test")
          messages <- session.stream.compile.toList
        yield
          assert(
            messages.exists(_.isInstanceOf[AssistantMessage]),
            s"Expected AssistantMessage: $messages"
          )
          assert(
            messages.exists(_.isInstanceOf[ResultMessage]),
            s"Expected ResultMessage: $messages"
          )
      }
      .guarantee(IO { SessionMockCliScript.cleanup(script): Unit })
  }

  // ============================================================
  // I4: Resource cleanup after process crash does not hang
  // ============================================================

  test("Resource cleanup completes without hanging after process dies") {
    val script = SessionMockCliScript.createImmediateExitScript(exitCode = 1)
    val options = SessionOptions().withClaudeExecutable(script.toString)
    // The Resource finalizer should complete cleanly even when the process has died
    ClaudeCode
      .session(options)
      .use { session =>
        // Wait for process to die
        IO.sleep(scala.concurrent.duration.FiniteDuration(200, "ms")) *>
          session.sessionId.map(_ => ())
      }
      .guarantee(IO { SessionMockCliScript.cleanup(script): Unit })
    // If we get here without timeout, the test passes
  }
