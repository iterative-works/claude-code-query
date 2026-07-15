// PURPOSE: Integration test for the real control-protocol interrupt against a mock CLI process
// PURPOSE: Interrupt is correlated by request_id, its error result bumps resultsSeen, and the session survives

package works.iterative.claude.zio

import zio.*
import zio.test.*
import works.iterative.claude.core.model.*
import works.iterative.claude.zio.internal.testing.{ClaudeZioSpec, MockCliScript}

object SessionInterruptIntegrationTest extends ClaudeZioSpec:

  private val initLine =
    """{"type":"system","subtype":"init","session_id":"sess-int"}"""
  private val errorResult =
    """{"type":"result","subtype":"error_during_execution","duration_ms":1,"duration_api_ms":1,"is_error":true,"num_turns":1,"session_id":"sess-int"}"""
  private val assistantLine =
    """{"type":"assistant","message":{"content":[{"type":"text","text":"after interrupt"}]}}"""
  private val normalResult =
    """{"type":"result","subtype":"conversation_result","duration_ms":1,"duration_api_ms":1,"is_error":false,"num_turns":1,"session_id":"sess-int"}"""

  private def options(script: os.Path): SessionOptions =
    SessionOptions.defaults.withClaudeExecutable(script.toString)

  def spec = suite("Session interrupt (integration)")(
    test("interrupt is correlated, bumps resultsSeen with an error result, and the session survives"):
      val script = MockCliScript.interruptSessionScript(
        initLine,
        errorResult,
        List(assistantLine, normalResult)
      )
      ZIO.scoped:
        for
          session  <- ClaudeCode.session(options(script))
          outcome  <- session.interrupt
          // The interrupted turn's error result (origin absent) bumps resultsSeen.
          _        <- session.state.repeatUntil(_.resultsSeen > 0)
          afterInt <- session.state
          // The session survives: a follow-up turn still completes.
          followUp <- session.sendAndAwait(UserInput("are you alive?"))
        yield assertTrue(
          outcome == InterruptOutcome(Nil),
          afterInt.lastResult.exists(_.isError),
          !followUp.isError
        )
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(Duration.fromSeconds(30))
