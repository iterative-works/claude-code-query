// PURPOSE: Unit tests for the ZIO Session implementation driven through its backing primitives
// PURPOSE: Exercises send/info/interrupt/awaitResultAfter/state without spawning a real process

package works.iterative.claude.zio

import zio.*
import java.nio.charset.StandardCharsets
import zio.test.*
import io.circe.{Json, parser}
import works.iterative.claude.core.{CLIError, SessionProcessDied}
import works.iterative.claude.core.model.*
import works.iterative.claude.zio.internal.cli.SessionProcess
import works.iterative.claude.zio.internal.testing.ClaudeZioSpec

object SessionTest extends ClaudeZioSpec:

  /** The primitives behind a [[Session]], exposed so a test can drive the reader
    * side by hand (fold state, complete promises, route control responses).
    */
  final case class Rig(
      session: Session,
      stdinQueue: Queue[Chunk[Byte]],
      eventsHub: Hub[Message],
      stateChangesHub: Hub[SessionState],
      stateRef: Ref[SessionState],
      sessionIdRef: Ref[Option[String]],
      idKnown: Promise[Nothing, Unit],
      terminated: Promise[Nothing, SessionEnd],
      pendingRequests: Ref[Map[String, Promise[CLIError, ControlResponse]]],
      aliveRef: Ref[Boolean]
  )

  private def rig(
      alive: Boolean = true,
      state: SessionState = SessionState.initial,
      sessionId: Option[String] = Some("sess-1"),
      idAlreadyKnown: Boolean = true
  ): UIO[Rig] =
    for
      stdinQueue      <- Queue.unbounded[Chunk[Byte]]
      eventsHub       <- Hub.sliding[Message](16)
      stateChangesHub <- Hub.sliding[SessionState](16)
      stateRef        <- Ref.make(state)
      sessionIdRef    <- Ref.make(sessionId)
      idKnown         <- Promise.make[Nothing, Unit]
      _               <- idKnown.succeed(()).when(idAlreadyKnown)
      terminated      <- Promise.make[Nothing, SessionEnd]
      pendingRequests <-
        Ref.make(Map.empty[String, Promise[CLIError, ControlResponse]])
      requestCounter  <- Ref.make(0L)
      aliveRef        <- Ref.make(alive)
    yield Rig(
      SessionProcess.make(
        stdinQueue,
        eventsHub,
        stateChangesHub,
        stateRef,
        sessionIdRef,
        idKnown,
        terminated,
        pendingRequests,
        requestCounter,
        aliveRef
      ),
      stdinQueue,
      eventsHub,
      stateChangesHub,
      stateRef,
      sessionIdRef,
      idKnown,
      terminated,
      pendingRequests,
      aliveRef
    )

  private val input  = UserInput("hello")
  private def result(
      origin: Option[ResultOrigin] = None,
      isError: Boolean = false,
      sessionId: String = "sess-1"
  ): ResultMessage =
    ResultMessage("success", 1, 1, isError, 1, sessionId, origin = origin)

  private def takeLine(queue: Queue[Chunk[Byte]]): UIO[String] =
    queue.take.map(chunk => new String(chunk.toArray, StandardCharsets.UTF_8))

  def spec = suite("Session")(
    test("send fails with the recorded end error when the process has died"):
      for
        r <-
          rig(
            alive = false,
            state = SessionState.initial.copy(ended =
              Some(SessionEnd(Some(1), Some(SessionProcessDied(Some(1), "boom"))))
            )
          )
        error <- r.session.send(input).flip
      yield assertTrue(error == SessionProcessDied(Some(1), "boom")),
    test("send fails with SessionProcessDied(None) when dead without a recorded end"):
      for
        r     <- rig(alive = false)
        error <- r.session.send(input).flip
      yield assertTrue(error == SessionProcessDied(None, "")),
    test("send fails when the stdin queue is shut down"):
      for
        r     <- rig(alive = true)
        _     <- r.stdinQueue.shutdown
        error <- r.session.send(input).flip
      yield assertTrue(error == SessionProcessDied(None, "")),
    test("send writes the block-array user message carrying the session id"):
      val structured = UserInput(
        text = "hello </interactive>",
        context = List(ContextItem.Viewing("https://example.test"))
      )
      for
        r    <- rig(sessionId = Some("sess-1"))
        _    <- r.session.send(structured)
        line <- takeLine(r.stdinQueue)
        json  = parser.parse(line).toOption.get
        blocks = json.hcursor
                   .downField("message")
                   .get[List[Json]]("content")
                   .toOption
                   .get
        texts = blocks.flatMap(_.hcursor.get[String]("text").toOption)
      yield assertTrue(
        json.hcursor.get[String]("session_id").toOption.contains("sess-1"),
        line.endsWith("\n"),
        blocks.size > 1,
        texts.last == "hello </interactive>"
      ),
    test("send is fire-and-forget: it returns without advancing resultsSeen"):
      for
        r     <- rig()
        _     <- r.session.send(input)
        state <- r.session.state
      yield assertTrue(state.resultsSeen == 0L),
    test("awaitResultAfter returns immediately when the counter already advanced"):
      val landed = result()
      for
        r <- rig(state =
               SessionState.initial.copy(resultsSeen = 1, lastResult = Some(landed))
             )
        // No reader running; a hang here would fail the suite's timeout.
        got <- r.session.awaitResultAfter(0)
      yield assertTrue(got == landed),
    test("awaitResultAfter wakes when a result is folded after the call starts"):
      val landed = result()
      for
        r     <- rig()
        fiber <- r.session.awaitResultAfter(0).fork
        _     <- fiber.status.repeatUntil(_.isSuspended)
        next  <- r.stateRef.updateAndGet(SessionState.fold(_, landed))
        _     <- r.stateChangesHub.publish(next)
        got   <- fiber.join
      yield assertTrue(got == landed),
    test("awaitResultAfter is not woken by an origin-present notification"):
      val notification = result(origin = Some(ResultOrigin.TaskNotification))
      val real         = result(isError = false)
      for
        r     <- rig()
        fiber <- r.session.awaitResultAfter(0).fork
        _     <- fiber.status.repeatUntil(_.isSuspended)
        // A notification bumps notificationsSeen, not resultsSeen — no wake.
        n1    <- r.stateRef.updateAndGet(SessionState.fold(_, notification))
        _     <- r.stateChangesHub.publish(n1)
        still <- fiber.status
        // A real result then wakes it.
        n2    <- r.stateRef.updateAndGet(SessionState.fold(_, real))
        _     <- r.stateChangesHub.publish(n2)
        got   <- fiber.join
      yield assertTrue(!still.isDone, got == real),
    test("awaitResultAfter fails with the recorded end error on process death"):
      for
        r <-
          rig(state =
            SessionState.initial.copy(ended =
              Some(SessionEnd(Some(2), Some(SessionProcessDied(Some(2), "died"))))
            )
          )
        error <- r.session.awaitResultAfter(0).flip
      yield assertTrue(error == SessionProcessDied(Some(2), "died")),
    test("state reads the current SessionState"):
      val s = SessionState.initial.copy(resultsSeen = 3, notificationsSeen = 1)
      for
        r     <- rig(state = s)
        state <- r.session.state
      yield assertTrue(state == s),
    test("info returns the session id once known"):
      for
        r    <- rig(sessionId = Some("abc"))
        info <- r.session.info
      yield assertTrue(info == SessionInfo("abc")),
    test("interrupt writes a control_request and returns the routed outcome"):
      for
        r     <- rig()
        fiber <- r.session.interrupt.fork
        line  <- takeLine(r.stdinQueue)
        cursor = parser.parse(line).toOption.get.hcursor
        requestId = cursor.get[String]("request_id").toOption.get
        // Simulate the reader routing the vendor's control_response.
        promise <- r.pendingRequests.get.map(_(requestId))
        _       <- promise.succeed(
                     ControlResponse(
                       requestId,
                       "success",
                       Json.obj(
                         "still_queued" -> Json.arr(Json.fromString("queued-1"))
                       )
                     )
                   )
        outcome <- fiber.join
        leftover <- r.pendingRequests.get
      yield assertTrue(
        cursor.get[String]("type").toOption.contains("control_request"),
        cursor
          .downField("request")
          .get[String]("subtype")
          .toOption
          .contains("interrupt"),
        outcome == InterruptOutcome(List("queued-1")),
        leftover.isEmpty // registry cleaned up, no promise leak
      ),
    test("terminated resolves with the recorded SessionEnd"):
      val end = SessionEnd(Some(0), None)
      for
        r   <- rig()
        _   <- r.terminated.succeed(end)
        got <- r.session.terminated
      yield assertTrue(got == end),
    test("two events subscribers both see the same messages"):
      val a = AssistantMessage(List(TextBlock("one")))
      val b = AssistantMessage(List(TextBlock("two")))
      for
        r    <- rig()
        sub1 <- r.session.events.take(2).runCollect.fork
        sub2 <- r.session.events.take(2).runCollect.fork
        _    <- ZIO.sleep(50.millis)
        _    <- r.eventsHub.publish(a)
        _    <- r.eventsHub.publish(b)
        one  <- sub1.join
        two  <- sub2.join
      yield assertTrue(
        one.toList == List(a, b),
        two.toList == List(a, b)
      ),
    // Regression (PROC-589 lineage): the wait for the session id must not hang
    // when the process ends before the CLI names the session, even inside an
    // uninterruptible region. `info` completes by racing `terminated` (promise
    // completion, not interrupt), so an uninterruptible region cannot wedge it.
    test("info does not hang under uninterruptible when the process ends unnamed"):
      for
        r    <- rig(sessionId = None, idAlreadyKnown = false)
        end   = SessionEnd(Some(1), Some(SessionProcessDied(Some(1), "gone")))
        _    <- r.terminated.succeed(end)
        done <- Promise.make[Nothing, Exit[CLIError, SessionInfo]]
        _    <- ZIO
                  .uninterruptible(r.session.info.exit)
                  .flatMap(done.succeed)
                  .forkDaemon
        observed <- done.await.timeout(10.seconds)
      yield assertTrue(
        observed.isDefined,
        observed.get == Exit.fail(SessionProcessDied(Some(1), "gone"))
      )
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(Duration.fromSeconds(30))
