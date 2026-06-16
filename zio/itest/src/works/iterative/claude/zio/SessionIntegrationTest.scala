// PURPOSE: Integration test driving a multi-turn Session against a mock session process
// PURPOSE: Verifies init session-id extraction, stdin send, and end-of-turn streaming

package works.iterative.claude.zio

import zio.*
import zio.test.*
import works.iterative.claude.core.model.*
import works.iterative.claude.zio.internal.testing.{ClaudeZioSpec, MockCliScript}

object SessionIntegrationTest extends ClaudeZioSpec:

  private val initLine =
    """{"type":"system","subtype":"init","session_id":"sess-itest"}"""
  private val assistantLine =
    """{"type":"assistant","message":{"content":[{"type":"text","text":"hi there"}]}}"""
  private val resultLine =
    """{"type":"result","subtype":"conversation_result","duration_ms":1,"duration_api_ms":1,"is_error":false,"num_turns":1,"session_id":"sess-itest"}"""

  def spec = suite("Session (integration)")(
    test("completes a turn against a mock session process"):
      val script  =
        MockCliScript.sessionScript(initLine, List(assistantLine, resultLine))
      val options = SessionOptions.defaults.withClaudeExecutable(script.toString)
      ZIO.scoped:
        for
          session  <- ClaudeCode.session(options)
          id       <- session.sessionId
          _        <- session.send("hello")
          messages <- session.stream.runCollect
        yield assertTrue(
          id == "sess-itest",
          messages.contains(AssistantMessage(List(TextBlock("hi there")))),
          messages.lastOption.exists(_.isInstanceOf[ResultMessage])
        ),
    test("skips a malformed JSON line mid-turn and still completes the turn"):
      val malformedLine = "{ not valid json"
      val script        = MockCliScript.sessionScript(
        initLine,
        List(malformedLine, assistantLine, resultLine)
      )
      val options =
        SessionOptions.defaults.withClaudeExecutable(script.toString)
      ZIO.scoped:
        for
          session  <- ClaudeCode.session(options)
          _        <- session.send("hello")
          messages <- session.stream.runCollect
        yield assertTrue(
          messages.contains(AssistantMessage(List(TextBlock("hi there")))),
          messages.count(_.isInstanceOf[ResultMessage]) == 1,
          messages.lastOption.exists(_.isInstanceOf[ResultMessage])
        ),
    test("updates the session id from a turn's ResultMessage"):
      val updatedResultLine =
        """{"type":"result","subtype":"conversation_result","duration_ms":1,"duration_api_ms":1,"is_error":false,"num_turns":1,"session_id":"sess-after-turn"}"""
      val script  = MockCliScript.sessionScript(
        initLine,
        List(assistantLine, updatedResultLine)
      )
      val options =
        SessionOptions.defaults.withClaudeExecutable(script.toString)
      ZIO.scoped:
        for
          session   <- ClaudeCode.session(options)
          initialId <- session.sessionId
          _         <- session.send("hello")
          _         <- session.stream.runCollect
          updatedId <- session.sessionId
        yield assertTrue(
          initialId == "sess-itest",
          updatedId == "sess-after-turn"
        )
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(Duration.fromSeconds(30))
