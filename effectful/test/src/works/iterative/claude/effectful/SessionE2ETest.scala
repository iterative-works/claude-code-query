// PURPOSE: End-to-end tests for effectful Session using the real Claude Code CLI
// PURPOSE: Tests are gated on CLI availability and skip gracefully when not present
package works.iterative.claude.effectful

import cats.effect.IO
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.testing.TestingLogger
import works.iterative.claude.core.model.*

class SessionE2ETest extends CatsEffectSuite:

  given Logger[IO] = TestingLogger.impl[IO]()

  private def isClaudeCliInstalled(): Boolean =
    try
      val process = new ProcessBuilder("claude", "--version").start()
      process.waitFor() == 0
    catch case _: Exception => false

  private def hasApiKeyOrCredentials(): Boolean =
    val hasApiKey = sys.env.contains("ANTHROPIC_API_KEY")
    val homeDir = sys.env.get("HOME").orElse(sys.env.get("USERPROFILE"))
    val hasCredentials = homeDir.exists { home =>
      val path = java.nio.file.Paths.get(home, ".claude", ".credentials.json")
      java.nio.file.Files.exists(path)
    }
    hasApiKey || hasCredentials

  private def assumeClaudeAvailable(): Unit =
    assume(isClaudeCliInstalled(), "Test requires Claude CLI to be installed")
    assume(
      hasApiKeyOrCredentials(),
      "Test requires API key or credentials file"
    )

  // ============================================================
  // E1: Single-turn session with real CLI
  // ============================================================

  test("E2E: real CLI session completes a single turn") {
    IO(assumeClaudeAvailable()) *>
      ClaudeCode.session(SessionOptions()).use { session =>
        for
          _ <- session.send("What is 1+1? Reply with just the number.")
          messages <- session.stream.compile.toList
        yield
          assert(messages.nonEmpty, "Expected messages from real CLI session")
          assert(
            messages.exists(_.isInstanceOf[AssistantMessage]),
            "Expected AssistantMessage in response"
          )
          assert(
            messages.exists(_.isInstanceOf[ResultMessage]),
            "Expected ResultMessage signalling end of turn"
          )
      }
  }

  // ============================================================
  // E2: Multi-turn with context dependency
  // ============================================================

  test("E2E: two-turn conversation preserves context across turns") {
    IO(assumeClaudeAvailable()) *>
      ClaudeCode.session(SessionOptions()).use { session =>
        for
          _ <- session.send("Remember the number 42. Reply only with 'OK'.")
          _ <- session.stream.compile.drain
          _ <- session.send(
            "What number did I ask you to remember? Reply with just the number."
          )
          secondTurnMessages <- session.stream.compile.toList
        yield
          val assistantMessages =
            secondTurnMessages.collect { case a: AssistantMessage => a }
          assert(
            assistantMessages.nonEmpty,
            "Expected AssistantMessage in second turn"
          )
          val responseText = assistantMessages
            .flatMap(_.content.collect { case TextBlock(t) => t })
            .mkString
          assert(
            responseText.contains("42"),
            s"Expected second turn to reference '42', got: $responseText"
          )
      }
  }

  // ============================================================
  // E3: Session ID is valid after real CLI interaction
  // ============================================================

  test("E2E: session ID is a valid non-pending value after first turn") {
    IO(assumeClaudeAvailable()) *>
      ClaudeCode.session(SessionOptions()).use { session =>
        for
          _ <- session.send("What is 1+1? Reply with just the number.")
          _ <- session.stream.compile.drain
          id <- session.sessionId
        yield
          assert(
            id != "pending",
            s"Expected a real session ID after first turn, got: $id"
          )
          assert(
            id.nonEmpty,
            "Session ID should not be empty after first turn"
          )
      }
  }
