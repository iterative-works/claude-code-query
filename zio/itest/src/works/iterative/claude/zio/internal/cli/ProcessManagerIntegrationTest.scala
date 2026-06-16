// PURPOSE: Integration tests spawning a mock CLI process to exercise ProcessManager
// PURPOSE: Verifies streaming, non-zero exit handling, and timeout handling end-to-end

package works.iterative.claude.zio.internal.cli

import zio.*
import zio.test.*
import scala.concurrent.duration.FiniteDuration
import works.iterative.claude.core.{ProcessExecutionError, ProcessTimeoutError}
import works.iterative.claude.core.model.*
import works.iterative.claude.zio.internal.testing.{ClaudeZioSpec, MockCliScript}

object ProcessManagerIntegrationTest extends ClaudeZioSpec:

  private val initLine =
    """{"type":"system","subtype":"init","session_id":"s1"}"""
  private val assistantLine =
    """{"type":"assistant","message":{"content":[{"type":"text","text":"hello"}]}}"""
  private val resultLine =
    """{"type":"result","subtype":"conversation_result","duration_ms":1,"duration_api_ms":1,"is_error":false,"num_turns":1,"session_id":"s1"}"""

  def spec = suite("ProcessManager (integration)")(
    test("streams parsed messages from a mock CLI"):
      val script =
        MockCliScript.queryScript(List(initLine, assistantLine, resultLine))
      for messages <- ProcessManager
                         .executeProcess(
                           script.toString,
                           Nil,
                           QueryOptions.simple("hi")
                         )
                         .runCollect
      yield assertTrue(
        messages.size == 3,
        messages.contains(AssistantMessage(List(TextBlock("hello"))))
      ),
    test("fails with ProcessExecutionError on a non-zero exit"):
      val script =
        MockCliScript.queryScript(Nil, exitCode = 2, stderr = Some("boom"))
      for error <- ProcessManager
                     .executeProcess(
                       script.toString,
                       Nil,
                       QueryOptions.simple("hi")
                     )
                     .runCollect
                     .flip
      yield assertTrue(
        error match
          case ProcessExecutionError(2, stderr, _) => stderr.contains("boom")
          case _                                   => false
      ),
    test("fails with ProcessTimeoutError when the process exceeds the timeout"):
      val script  = MockCliScript.hangingScript(30)
      val options = QueryOptions
        .simple("hi")
        .withTimeout(FiniteDuration(500, "milliseconds"))
      for error <- ProcessManager
                     .executeProcess(script.toString, Nil, options)
                     .runCollect
                     .flip
      yield assertTrue(error.isInstanceOf[ProcessTimeoutError]),
    test("inheritEnvironment=false launches with a cleared environment"):
      val cleared   = ProcessManager.baseCommand(
        "printenv",
        Nil,
        inheritEnvironment = Some(false),
        environmentVariables = Some(Map("FOO" -> "bar")),
        cwd = None
      )
      val inherited = ProcessManager.baseCommand(
        "printenv",
        Nil,
        inheritEnvironment = Some(true),
        environmentVariables = Some(Map("FOO" -> "bar")),
        cwd = None
      )
      for
        clearedEnv   <- cleared.lines
        inheritedEnv <- inherited.lines
      yield assertTrue(
        clearedEnv.toList == List("FOO=bar"),
        inheritedEnv.contains("FOO=bar"),
        inheritedEnv.exists(_.startsWith("PATH="))
      )
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(Duration.fromSeconds(60))
