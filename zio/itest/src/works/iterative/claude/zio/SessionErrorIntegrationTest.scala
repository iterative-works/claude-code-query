// PURPOSE: Integration test for Session error handling against a real crashing process
// PURPOSE: Verifies a mid-turn non-zero exit is surfaced as SessionProcessDied

package works.iterative.claude.zio

import zio.*
import zio.test.*
import works.iterative.claude.core.SessionProcessDied
import works.iterative.claude.zio.internal.testing.{ClaudeZioSpec, MockCliScript}

object SessionErrorIntegrationTest extends ClaudeZioSpec:

  private val initLine =
    """{"type":"system","subtype":"init","session_id":"sess-crash"}"""
  private val partialLine =
    """{"type":"assistant","message":{"content":[{"type":"text","text":"partial"}]}}"""

  // Polls send until the dead process is observed, mirroring the race-tolerant
  // approach the effectful module uses: process death is detected by a
  // background reader, so the first send may still land before aliveRef flips.
  private def sendUntilDead(
      session: Session,
      remaining: Int
  ): Task[CLIError] =
    if remaining <= 0 then
      ZIO.fail(new RuntimeException("send never failed despite a dead process"))
    else
      session
        .send("hi")
        .foldZIO(
          error => ZIO.succeed(error),
          _ => ZIO.sleep(50.millis) *> sendUntilDead(session, remaining - 1)
        )

  def spec = suite("Session error (integration)")(
    test("stream surfaces SessionProcessDied when the process exits mid-turn"):
      val script  =
        MockCliScript.crashMidTurnScript(initLine, partialLine, exitCode = 3)
      val options = SessionOptions.defaults.withClaudeExecutable(script.toString)
      ZIO.scoped:
        for
          session <- ClaudeCode.session(options)
          _       <- session.send("hello")
          error   <- session.stream.runCollect.flip
        yield assertTrue(
          error == SessionProcessDied(Some(3), "")
        ),
    test("send to a process that has already exited fails with SessionProcessDied"):
      val script  = MockCliScript.queryScript(Nil, exitCode = 1)
      val options = SessionOptions.defaults.withClaudeExecutable(script.toString)
      ZIO.scoped:
        for
          session <- ClaudeCode.session(options)
          error   <- sendUntilDead(session, remaining = 100)
        yield assertTrue(error.isInstanceOf[SessionProcessDied])
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(Duration.fromSeconds(30))
