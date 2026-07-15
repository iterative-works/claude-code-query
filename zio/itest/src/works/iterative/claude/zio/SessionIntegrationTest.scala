// PURPOSE: Integration tests driving the turn-less Session against mock CLI processes
// PURPOSE: Pins sendAndAwait, the no-window completion guarantee, notification routing, merge, and Hub fan-out

package works.iterative.claude.zio

import zio.*
import zio.test.*
import works.iterative.claude.core.SessionProcessDied
import works.iterative.claude.core.model.*
import works.iterative.claude.zio.internal.testing.{ClaudeZioSpec, MockCliScript}

object SessionIntegrationTest extends ClaudeZioSpec:

  private val initLine =
    """{"type":"system","subtype":"init","session_id":"sess-itest"}"""
  private val assistantLine =
    """{"type":"assistant","message":{"content":[{"type":"text","text":"hi there"}]}}"""
  private val resultLine =
    """{"type":"result","subtype":"conversation_result","duration_ms":1,"duration_api_ms":1,"is_error":false,"num_turns":1,"session_id":"sess-itest"}"""
  private val notificationLine =
    """{"type":"result","subtype":"conversation_result","duration_ms":1,"duration_api_ms":1,"is_error":false,"num_turns":1,"session_id":"sess-itest","origin":{"kind":"task-notification"}}"""

  private def options(script: os.Path): SessionOptions =
    SessionOptions.defaults.withClaudeExecutable(script.toString)

  private val input = UserInput("hello")

  def spec = suite("Session (integration)")(
    test("sendAndAwait returns the next real result"):
      val script = MockCliScript.sessionScript(initLine, List(assistantLine, resultLine))
      ZIO.scoped:
        for
          session <- mockCliSession(options(script))
          result  <- session.sendAndAwait(input)
        yield assertTrue(
          result.sessionId.value == "sess-itest",
          result.origin.isEmpty,
          !result.isError
        ),
    test("a result landing before awaitResultAfter still returns immediately"):
      val script = MockCliScript.sessionScript(initLine, List(assistantLine, resultLine))
      ZIO.scoped:
        for
          session <- mockCliSession(options(script))
          _       <- session.send(input)
          // Let the result land and fold BEFORE we ask for it.
          _       <- session.state.repeatUntil(_.resultsSeen > 0)
          // The counter has already advanced: this must not block.
          result  <- session.awaitResultAfter(0)
        yield assertTrue(result.origin.isEmpty),
    test("an origin-present notification does not wake a waiter but bumps notificationsSeen"):
      // The turn emits a task-notification result first, then the real result.
      val script =
        MockCliScript.sessionScript(initLine, List(notificationLine, resultLine))
      ZIO.scoped:
        for
          session <- mockCliSession(options(script))
          result  <- session.sendAndAwait(input)
          state   <- session.state
        yield assertTrue(
          result.origin.isEmpty, // woken by the real result, not the notification
          state.resultsSeen == 1L,
          state.notificationsSeen == 1L
        ),
    test("two merged sends wake both concurrent waiters on the single result"):
      val script = MockCliScript.mergedTurnScript(initLine, List(assistantLine, resultLine))
      ZIO.scoped:
        for
          session <- mockCliSession(options(script))
          s0      <- session.state
          _       <- session.send(input)
          _       <- session.send(UserInput("second"))
          results <- ZIO.collectAllPar(
                       Chunk(
                         session.awaitResultAfter(s0.resultsSeen),
                         session.awaitResultAfter(s0.resultsSeen)
                       )
                     )
        yield assertTrue(
          results.size == 2,
          results.forall(!_.isError)
        ),
    test("send is fire-and-forget: it returns before the result lands"):
      val script =
        MockCliScript.delayedTurnScript(initLine, List(resultLine), delaySeconds = 2)
      ZIO.scoped:
        for
          session <- mockCliSession(options(script))
          _       <- session.send(input)
          state   <- session.state // read right after send returns
        yield assertTrue(state.resultsSeen == 0L),
    test("send writes the UserInput block array to the process stdin"):
      val capture = os.temp()
      val script  =
        MockCliScript.stdinCaptureScript(initLine, capture, List(resultLine))
      val structured = UserInput(
        text = "verbatim </interactive>",
        context = List(ContextItem.Viewing("https://example.test"))
      )
      ZIO.scoped:
        for
          session <- mockCliSession(options(script))
          _       <- session.sendAndAwait(structured)
          written <- ZIO.attemptBlocking(os.read(capture)).orDie
        yield
          // Parse the captured stdin line: message.content must be exactly the
          // block array UserInput.encode produced, verbatim text untouched.
          val sendLine = written.linesIterator.next()
          val blockTexts = io.circe.parser
            .parse(sendLine)
            .toOption
            .flatMap(
              _.hcursor
                .downField("message")
                .get[List[io.circe.Json]]("content")
                .toOption
            )
            .getOrElse(Nil)
            .flatMap(_.hcursor.get[String]("text").toOption)
          assertTrue(
            sendLine.contains("\"type\":\"user\""),
            blockTexts == UserInput.encode(structured).map(_.text)
          ),
    test("two events subscribers both see the same messages"):
      val script = MockCliScript.sessionScript(initLine, List(assistantLine, resultLine))
      ZIO.scoped:
        for
          session <- mockCliSession(options(script))
          // Compare only the turn's messages: the init system line is emitted
          // at process startup and races the subscriptions, so a subscriber
          // may or may not catch it without affecting broadcast semantics.
          collect  =
            session.events
              .filter(m =>
                m.isInstanceOf[AssistantMessage] || m.isInstanceOf[ResultMessage]
              )
              .takeUntil(_.isInstanceOf[ResultMessage])
              .runCollect
          sub1    <- collect.fork
          sub2    <- collect.fork
          // Wait until both collectors have subscribed and are blocked pulling
          // the hub (suspended), so neither can miss the turn's early messages —
          // deterministic where a fixed sleep would race under load.
          _       <- sub1.status.repeatUntil(_.isSuspended)
          _       <- sub2.status.repeatUntil(_.isSuspended)
          _       <- session.send(input)
          one     <- sub1.join
          two     <- sub2.join
        yield assertTrue(
          one == two,
          one.exists(_.isInstanceOf[AssistantMessage]),
          one.lastOption.exists(_.isInstanceOf[ResultMessage])
        ),
    test("stateChanges completes when the session process ends"):
      val script = MockCliScript.crashMidTurnScript(initLine, assistantLine)
      ZIO.scoped:
        for
          session <- mockCliSession(options(script))
          drained <- session.stateChanges.runDrain.fork
          _       <- session.send(input)
          _       <- session.terminated
          // The stateChanges stream must complete once the process ends; a hang
          // here would leave `done` empty.
          done    <- drained.join.timeout(5.seconds)
        yield assertTrue(done.isDefined),
    test("a process that writes stderr then dies surfaces that stderr in the death error"):
      val script = MockCliScript.crashMidTurnScript(
        initLine,
        assistantLine,
        exitCode = 3,
        stderr = Some("fatal: mock boom")
      )
      ZIO.scoped:
        for
          session <- mockCliSession(options(script))
          _       <- session.send(input)
          end     <- session.terminated
        yield assertTrue(
          end.error.exists:
            case died: SessionProcessDied =>
              died.exitCode.contains(3) && died.stderr.contains("fatal: mock boom")
            case _ => false
        ),
    test("skips a malformed JSON line mid-turn and still completes the turn"):
      val script = MockCliScript.sessionScript(
        initLine,
        List("{ not valid json", assistantLine, resultLine)
      )
      ZIO.scoped:
        for
          session <- mockCliSession(options(script))
          result  <- session.sendAndAwait(input)
          state   <- session.state
        yield assertTrue(!result.isError, state.resultsSeen == 1L),
    test("info reflects a session id updated by a turn's result"):
      val updatedResult =
        """{"type":"result","subtype":"conversation_result","duration_ms":1,"duration_api_ms":1,"is_error":false,"num_turns":1,"session_id":"sess-after-turn"}"""
      val script = MockCliScript.sessionScript(initLine, List(updatedResult))
      ZIO.scoped:
        for
          session <- mockCliSession(options(script))
          before  <- session.info
          _       <- session.sendAndAwait(input)
          after   <- session.info
        yield assertTrue(
          before == SessionInfo(SessionId("sess-itest")),
          after == SessionInfo(SessionId("sess-after-turn"))
        ),
    test("a configured archive with no vendor tree does not fail the session"):
      val script  = MockCliScript.sessionScript(initLine, List(resultLine))
      val emptyDir = os.temp.dir()
      val archive  =
        ArchiveConfig(
          vendorProjectsDir = emptyDir,
          archiveDir = os.temp.dir(),
          cwd = os.pwd
        )
      ZIO.scoped:
        for
          session <- mockCliSession(options(script), archive = Some(archive))
          result  <- session.sendAndAwait(input)
        yield assertTrue(!result.isError)
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(Duration.fromSeconds(60))
