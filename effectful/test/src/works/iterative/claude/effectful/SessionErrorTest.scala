// PURPOSE: Tests for session error handling in the effectful (cats-effect/fs2) API
// PURPOSE: Covers process crash, malformed JSON resilience, and dead-process detection
package works.iterative.claude.effectful

import cats.effect.IO
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.testing.TestingLogger
import works.iterative.claude.core.{SessionProcessDied}
import works.iterative.claude.core.model.*
import works.iterative.claude.direct.internal.testing.SessionMockCliScript

class SessionErrorTest extends CatsEffectSuite:

  given Logger[IO] = TestingLogger.impl[IO]()

  // ============================================================
  // E1: send to dead process raises SessionProcessDied in IO
  // ============================================================

  test("send to a dead process raises SessionProcessDied in IO") {
    val script = SessionMockCliScript.createImmediateExitScript(exitCode = 1)
    val options = SessionOptions().withClaudeExecutable(script.toString)
    ClaudeCode
      .session(options)
      .use { session =>
        // Wait for the process to die
        IO.sleep(scala.concurrent.duration.FiniteDuration(200, "ms")) *>
          session.send("should fail").attempt.map { result =>
            assert(
              result.isLeft,
              s"Expected send to fail but got: $result"
            )
            assert(
              result.swap.toOption.exists(_.isInstanceOf[SessionProcessDied]),
              s"Expected SessionProcessDied, got: ${result.swap.toOption}"
            )
          }
      }
      .guarantee(IO { SessionMockCliScript.cleanup(script): Unit })
  }

  // ============================================================
  // E2: stream raises SessionProcessDied when process exits mid-turn
  // ============================================================

  test(
    "stream raises SessionProcessDied when process exits mid-turn (before ResultMessage)"
  ) {
    val script = SessionMockCliScript.createCrashMidTurnScript(exitCode = 1)
    val options = SessionOptions().withClaudeExecutable(script.toString)
    ClaudeCode
      .session(options)
      .use { session =>
        for
          _ <- session.send("trigger crash")
          result <- session.stream.compile.drain.attempt
        yield
          assert(result.isLeft, s"Expected stream to fail but got: $result")
          assert(
            result.swap.toOption.exists(_.isInstanceOf[SessionProcessDied]),
            s"Expected SessionProcessDied, got: ${result.swap.toOption}"
          )
      }
      .guarantee(IO { SessionMockCliScript.cleanup(script): Unit })
  }

  // ============================================================
  // E3: Malformed JSON line is logged and skipped; valid messages arrive
  // ============================================================

  test(
    "malformed JSON line is logged and skipped; valid messages in the same turn arrive normally"
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
            s"Expected AssistantMessage in stream: $messages"
          )
          assert(
            messages.exists(_.isInstanceOf[ResultMessage]),
            s"Expected ResultMessage in stream: $messages"
          )
      }
      .guarantee(IO { SessionMockCliScript.cleanup(script): Unit })
  }

  // ============================================================
  // E4: Session remains functional after malformed JSON
  // ============================================================

  test(
    "session remains functional after a malformed JSON line - ResultMessage arrives"
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
          val resultMessages = messages.collect { case r: ResultMessage => r }
          assertEquals(
            resultMessages.length,
            1,
            s"Expected 1 ResultMessage, got: $messages"
          )
      }
      .guarantee(IO { SessionMockCliScript.cleanup(script): Unit })
  }
