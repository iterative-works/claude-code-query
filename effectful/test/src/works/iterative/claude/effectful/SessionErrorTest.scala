// PURPOSE: Tests for session error handling in the effectful (cats-effect/fs2) API
// PURPOSE: Covers process crash, malformed JSON resilience, and dead-process detection
package works.iterative.claude.effectful

import cats.effect.IO
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.testing.TestingLogger
import works.iterative.claude.core.SessionProcessDied
import works.iterative.claude.core.model.*
import works.iterative.claude.direct.internal.testing.SessionMockCliScript
import scala.concurrent.duration.*

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
        // Poll until send fails (process has exited), with a timeout
        def pollUntilDead(remaining: Int): IO[Either[Throwable, Unit]] =
          if remaining <= 0 then IO.pure(Right(()))
          else
            session.send("should fail").attempt.flatMap {
              case Left(err) => IO.pure(Left(err))
              case Right(_)  =>
                IO.sleep(50.millis) *> pollUntilDead(remaining - 1)
            }

        pollUntilDead(100).map { result =>
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
    "malformed JSON line is logged and skipped; valid messages arrive with exactly 1 ResultMessage"
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

          val resultMessages = messages.collect { case r: ResultMessage => r }
          assertEquals(
            resultMessages.length,
            1,
            s"Expected exactly 1 ResultMessage, got: $messages"
          )
      }
      .guarantee(IO { SessionMockCliScript.cleanup(script): Unit })
  }

  // ============================================================
  // E4: send after process death with no pending error raises SessionProcessDied
  // ============================================================

  test(
    "send after process death with no pending error raises SessionProcessDied"
  ) {
    // Use a script that exits immediately — the stdout reader may not have
    // time to set a pendingError, exercising the None branch in pendingErrorRef
    val script = SessionMockCliScript.createImmediateExitScript(exitCode = 0)
    val options = SessionOptions().withClaudeExecutable(script.toString)
    ClaudeCode
      .session(options)
      .use { session =>
        // Poll until the process is detected as dead
        def pollUntilDead(remaining: Int): IO[Either[Throwable, Unit]] =
          if remaining <= 0 then IO.pure(Right(()))
          else
            session.send("should fail").attempt.flatMap {
              case Left(err) => IO.pure(Left(err))
              case Right(_)  =>
                IO.sleep(50.millis) *> pollUntilDead(remaining - 1)
            }

        pollUntilDead(100).map { result =>
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
