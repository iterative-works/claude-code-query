// PURPOSE: Integration test for Session death handling against real crashing processes
// PURPOSE: A mid-turn exit fails awaitResultAfter, resolves terminated, and records SessionState.ended

package works.iterative.claude.zio

import zio.*
import zio.test.*
import works.iterative.claude.core.SessionProcessDied
import works.iterative.claude.core.model.UserInput
import works.iterative.claude.zio.internal.testing.{ClaudeZioSpec, MockCliScript}

object SessionErrorIntegrationTest extends ClaudeZioSpec:

  private val initLine =
    """{"type":"system","subtype":"init","session_id":"sess-crash"}"""
  private val partialLine =
    """{"type":"assistant","message":{"content":[{"type":"text","text":"partial"}]}}"""

  private def options(script: os.Path): SessionOptions =
    SessionOptions.defaults.withClaudeExecutable(script.toString)

  private val input = UserInput("hello")

  // Death detection is async (a background reader observes the exit), and send
  // is fire-and-forget — so an early send may still land before aliveRef flips.
  // Poll until the dead process is observed, mirroring the pre-existing pattern.
  private def sendUntilDead(session: Session, remaining: Int): Task[CLIError] =
    if remaining <= 0 then
      ZIO.fail(new RuntimeException("send never failed despite a dead process"))
    else
      session
        .send(input)
        .foldZIO(
          error => ZIO.succeed(error),
          _ => ZIO.sleep(50.millis) *> sendUntilDead(session, remaining - 1)
        )

  def spec = suite("Session error (integration)")(
    test("awaitResultAfter fails, terminated resolves, and ended is recorded on a mid-turn exit"):
      val script = MockCliScript.crashMidTurnScript(initLine, partialLine, exitCode = 3)
      ZIO.scoped:
        for
          session <- mockCliSession(options(script))
          _       <- session.send(input)
          error   <- session.awaitResultAfter(0).flip
          end     <- session.terminated
          state   <- session.state
        yield assertTrue(
          error == SessionProcessDied(Some(3), ""),
          end.exitCode.contains(3),
          state.ended.exists(_.exitCode.contains(3))
        ),
    test("send to a process that has already exited fails with SessionProcessDied"):
      val script = MockCliScript.queryScript(Nil, exitCode = 1)
      ZIO.scoped:
        for
          session <- mockCliSession(options(script))
          error   <- sendUntilDead(session, remaining = 100)
        yield assertTrue(error.isInstanceOf[SessionProcessDied])
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(Duration.fromSeconds(30))
