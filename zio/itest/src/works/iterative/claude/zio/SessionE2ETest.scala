// PURPOSE: End-to-end tests for the ZIO Session against the real Claude Code CLI
// PURPOSE: Ports the direct/Ox SessionE2ETest so realtime stdin streaming is covered against the real CLI

package works.iterative.claude.zio

import zio.*
import zio.test.*
import works.iterative.claude.core.model.*
import works.iterative.claude.zio.internal.testing.ClaudeZioSpec

/** Exercises a long-lived ZIO Session against the real Claude Code CLI.
  *
  * The ZIO module's other session coverage drives a bash mock (MockCliScript),
  * which consumes whatever the stdin path writes regardless of framing. These
  * tests instead send a prompt to the real CLI and read the turn back without
  * closing the stream — the exact path the dashboard worker uses. Gated on CLI
  * availability and credentials so they ignore gracefully when unavailable.
  */
object SessionE2ETest extends ClaudeZioSpec:

  private def isClaudeCliInstalled(): Boolean =
    try
      val process = ProcessBuilder("claude", "--version").start()
      process.waitFor() == 0
    catch case _: Exception => false

  private def hasApiKeyOrCredentials(): Boolean =
    val hasApiKey = sys.env.contains("ANTHROPIC_API_KEY")
    val homeDir   = sys.env.get("HOME").orElse(sys.env.get("USERPROFILE"))
    val hasCredentials = homeDir.exists { home =>
      val path = java.nio.file.Paths.get(home, ".claude", ".credentials.json")
      java.nio.file.Files.exists(path)
    }
    hasApiKey || hasCredentials

  private val claudeAvailable: Boolean =
    isClaudeCliInstalled() && hasApiKeyOrCredentials()

  private val onlyIfClaude: TestAspectPoly =
    if claudeAvailable then TestAspect.identity else TestAspect.ignore

  def spec = suite("Session (e2e, real CLI)")(
    test("E2E: real CLI session completes a single turn"):
      ZIO.scoped:
        for
          session  <- ClaudeCode.session(SessionOptions.defaults)
          _        <- session.send("What is 1+1? Reply with just the number.")
          messages <- session.stream.runCollect
        yield assertTrue(
          messages.nonEmpty,
          messages.exists(_.isInstanceOf[AssistantMessage]),
          messages.exists(_.isInstanceOf[ResultMessage])
        ),
    test("E2E: two-turn conversation preserves context across turns"):
      ZIO.scoped:
        for
          session <- ClaudeCode.session(SessionOptions.defaults)
          _       <- session.send("Remember the number 42. Reply only with 'OK'.")
          _       <- session.stream.runCollect
          _       <- session.send(
                       "What number did I ask you to remember? Reply with just the number."
                     )
          second  <- session.stream.runCollect
        yield
          val responseText = second
            .collect { case a: AssistantMessage => a }
            .flatMap(_.content.collect { case TextBlock(t) => t })
            .mkString
          assertTrue(responseText.contains("42")),
    test("E2E: session ID is a valid non-pending value after first turn"):
      ZIO.scoped:
        for
          session <- ClaudeCode.session(SessionOptions.defaults)
          _       <- session.send("What is 1+1? Reply with just the number.")
          _       <- session.stream.runCollect
          id      <- session.sessionId
        yield assertTrue(id != "pending", id.nonEmpty),
    test("E2E: session ID remains valid and non-pending across multiple turns"):
      ZIO.scoped:
        for
          session     <- ClaudeCode.session(SessionOptions.defaults)
          _           <- session.send("What is 1+1? Reply with just the number.")
          _           <- session.stream.runCollect
          afterFirst  <- session.sessionId
          _           <- session.send("What is 2+2? Reply with just the number.")
          _           <- session.stream.runCollect
          afterSecond <- session.sessionId
        yield assertTrue(
          afterFirst != "pending",
          afterFirst.nonEmpty,
          afterSecond != "pending",
          afterSecond.nonEmpty,
          afterFirst == afterSecond
        )
  ) @@ onlyIfClaude
    @@ TestAspect.withLiveClock
    @@ TestAspect.timeout(Duration.fromSeconds(120))
    @@ TestAspect.sequential
