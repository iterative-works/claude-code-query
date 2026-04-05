// PURPOSE: End-to-end tests for Session using the real Claude Code CLI
// PURPOSE: Tests are gated on CLI availability and skip gracefully when not present
package works.iterative.claude.direct

import ox.*
import works.iterative.claude.core.model.*
import works.iterative.claude.direct.internal.testing.MockLogger

class SessionE2ETest extends munit.FunSuite:

  private def isClaudeCliInstalled(): Boolean =
    try
      val process = ProcessBuilder("claude", "--version").start()
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

  test("E2E: real CLI session completes a single turn") {
    given logger: MockLogger = MockLogger()
    supervised {
      assumeClaudeAvailable()

      val options = SessionOptions()
      val session = ClaudeCode.session(options)
      try
        session.send("What is 1+1? Reply with just the number.")
        val messages = session.stream().runToList()

        assert(messages.nonEmpty, "Expected messages from real CLI session")
        assert(
          messages.exists(_.isInstanceOf[AssistantMessage]),
          "Expected AssistantMessage in response"
        )
        assert(
          messages.exists(_.isInstanceOf[ResultMessage]),
          "Expected ResultMessage signalling end of turn"
        )
      finally session.close()
    }
  }

  test("E2E: two-turn conversation preserves context across turns") {
    given logger: MockLogger = MockLogger()
    supervised {
      assumeClaudeAvailable()

      val options = SessionOptions()
      val session = ClaudeCode.session(options)
      try
        session.send("Remember the number 42. Reply only with 'OK'.")
        val _ = session.stream().runToList()

        session.send(
          "What number did I ask you to remember? Reply with just the number."
        )
        val secondTurnMessages = session.stream().runToList()

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
      finally session.close()
    }
  }

  test("E2E: session ID is a valid non-pending value after first turn") {
    given logger: MockLogger = MockLogger()
    supervised {
      assumeClaudeAvailable()

      val options = SessionOptions()
      val session = ClaudeCode.session(options)
      try
        session.send("What is 1+1? Reply with just the number.")
        val _ = session.stream().runToList()

        assert(
          session.sessionId != "pending",
          s"Expected a real session ID after first turn, got: ${session.sessionId}"
        )
        assert(
          session.sessionId.nonEmpty,
          "Session ID should not be empty after first turn"
        )
      finally session.close()
    }
  }

  test(
    "E2E: session ID remains valid and non-pending across multiple turns"
  ) {
    given logger: MockLogger = MockLogger()
    supervised {
      assumeClaudeAvailable()

      val options = SessionOptions()
      val session = ClaudeCode.session(options)
      try
        session.send("What is 1+1? Reply with just the number.")
        val _ = session.stream().runToList()
        val afterFirstTurn = session.sessionId

        session.send("What is 2+2? Reply with just the number.")
        val _ = session.stream().runToList()
        val afterSecondTurn = session.sessionId

        assert(
          afterFirstTurn != "pending",
          s"Session ID should be non-pending after first turn, got: $afterFirstTurn"
        )
        assert(
          afterFirstTurn.nonEmpty,
          "Session ID should not be empty after first turn"
        )
        assert(
          afterSecondTurn != "pending",
          s"Session ID should be non-pending after second turn, got: $afterSecondTurn"
        )
        assert(
          afterSecondTurn.nonEmpty,
          "Session ID should not be empty after second turn"
        )
        assertEquals(
          afterFirstTurn,
          afterSecondTurn,
          "Session ID should remain stable across turns in the same session"
        )
      finally session.close()
    }
  }
