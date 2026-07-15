// PURPOSE: End-to-end tests for the turn-less ZIO Session against the real Claude Code CLI
// PURPOSE: Covers sendAndAwait round-trips, session id via info, context across turns, and a real interrupt

package works.iterative.claude.zio

import zio.*
import zio.test.*
import works.iterative.claude.core.model.*
import works.iterative.claude.zio.internal.testing.ClaudeZioSpec

/** Exercises a long-lived ZIO Session against the real Claude Code CLI.
  *
  * These drive the fire-and-forget send / readable-state completion path the
  * dashboard worker uses, and a real control-protocol interrupt (probe #5) kept
  * as a permanent regression. Gated on CLI availability and credentials so they
  * ignore gracefully when unavailable.
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

  private def userInput(text: String): UserInput = UserInput(text)

  def spec = suite("Session (e2e, real CLI)")(
    test("E2E: a single sendAndAwait completes a turn"):
      ZIO.scoped:
        for
          session <- ClaudeCode.session(SessionOptions.defaults)
          result  <- session.sendAndAwait(
                       userInput("What is 1+1? Reply with just the number.")
                     )
        yield assertTrue(!result.isError, result.origin.isEmpty),
    test("E2E: info yields a valid non-pending session id after a turn"):
      ZIO.scoped:
        for
          session <- ClaudeCode.session(SessionOptions.defaults)
          _       <- session.sendAndAwait(userInput("Reply with 'OK'."))
          info    <- session.info
        yield assertTrue(info.sessionId != "pending", info.sessionId.nonEmpty),
    test("E2E: two sequential turns preserve context"):
      ZIO.scoped:
        for
          session <- ClaudeCode.session(SessionOptions.defaults)
          _       <- session.sendAndAwait(
                       userInput("Remember the number 42. Reply only with 'OK'.")
                     )
          second  <- session.sendAndAwait(
                       userInput(
                         "What number did I ask you to remember? Reply with just the number."
                       )
                     )
        yield assertTrue(second.result.exists(_.contains("42"))),
    test("E2E: a real interrupt stops the turn, is an error result, and the session survives"):
      ZIO.scoped:
        for
          session <- ClaudeCode.session(SessionOptions.defaults)
          before  <- session.state
          // Start a slow turn, then interrupt it mid-flight.
          // A long generation keeps the turn reliably in flight at interrupt
          // time; a short task can finish before the interrupt lands.
          _       <- session.send(
                       userInput(
                         "Write a very long story, at least 3000 words, about a lighthouse keeper. Do not stop early."
                       )
                     )
          _       <- ZIO.sleep(3.seconds)
          outcome <- session.interrupt
          // The interrupted turn's error result bumps resultsSeen; the waiter
          // wakes with is_error rather than hanging.
          stopped <- session.awaitResultAfter(before.resultsSeen)
          // The session survives: a follow-up turn still completes.
          alive   <- session.sendAndAwait(userInput("Reply with 'ALIVE'."))
        yield assertTrue(
          outcome.stillQueued.forall(_.nonEmpty),
          stopped.isError,
          stopped.origin.isEmpty,
          !alive.isError
        )
  ) @@ onlyIfClaude
    @@ TestAspect.withLiveClock
    @@ TestAspect.timeout(Duration.fromSeconds(180))
    @@ TestAspect.sequential
