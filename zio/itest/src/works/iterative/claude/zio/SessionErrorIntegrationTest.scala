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
        )
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(Duration.fromSeconds(30))
