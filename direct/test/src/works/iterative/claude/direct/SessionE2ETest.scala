// PURPOSE: End-to-end tests for Session using the real Claude Code CLI
// PURPOSE: Tests are gated on CLI availability and skip gracefully when not present
package works.iterative.claude.direct

import ox.*
import works.iterative.claude.core.model.*
import works.iterative.claude.direct.Logger
import works.iterative.claude.direct.internal.testing.TestConstants

class SessionE2ETest extends munit.FunSuite:

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

  private def isClaudeCliInstalled(): Boolean =
    try
      val process = ProcessBuilder("claude", "--version").start()
      process.waitFor() == 0
    catch case _: Exception => false

  private def isNodeJsAvailable(): Boolean =
    try
      val process = ProcessBuilder("node", "--version").start()
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

  test("E2E: real CLI session completes a single turn") {
    given logger: MockLogger = MockLogger()
    supervised {
      assume(isClaudeCliInstalled(), "Test requires Claude CLI to be installed")
      assume(isNodeJsAvailable(), "Test requires Node.js to be available")
      assume(
        hasApiKeyOrCredentials(),
        "Test requires API key or credentials file"
      )

      val options = SessionOptions()
      val session = ClaudeCode.session(options)
      try
        val messages =
          session.send("What is 1+1? Reply with just the number.").runToList()

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

  test("E2E: session ID is a valid non-pending value after first turn") {
    given logger: MockLogger = MockLogger()
    supervised {
      assume(isClaudeCliInstalled(), "Test requires Claude CLI to be installed")
      assume(isNodeJsAvailable(), "Test requires Node.js to be available")
      assume(
        hasApiKeyOrCredentials(),
        "Test requires API key or credentials file"
      )

      val options = SessionOptions()
      val session = ClaudeCode.session(options)
      try
        val _ =
          session.send("What is 1+1? Reply with just the number.").runToList()

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
