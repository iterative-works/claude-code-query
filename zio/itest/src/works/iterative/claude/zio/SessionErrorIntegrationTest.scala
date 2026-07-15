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

  def spec = suite("Session error (integration)")(
    test("awaitResultAfter fails, terminated resolves, and ended is recorded on a mid-turn exit"):
      val script = MockCliScript.crashMidTurnScript(initLine, partialLine, exitCode = 3)
      ZIO.scoped:
        for
          session <- ClaudeCode.session(options(script))
          _       <- session.send(input)
          error   <- session.awaitResultAfter(0).flip
          end     <- session.terminated
          state   <- session.state
        yield assertTrue(
          error == SessionProcessDied(Some(3), ""),
          end.exitCode.contains(3),
          state.ended.exists(_.exitCode.contains(3))
        ),
    test("send to a process that never named the session fails with SessionProcessDied"):
      val script = MockCliScript.queryScript(Nil, exitCode = 1)
      ZIO.scoped:
        for
          session <- ClaudeCode.session(options(script))
          error   <- session.send(input).flip
        yield assertTrue(error.isInstanceOf[SessionProcessDied])
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(Duration.fromSeconds(30))
